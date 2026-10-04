package com.jedon.kellikanvas.feature.collection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class PhoneNasCredentials(val username: String, val password: CharArray, val endpoint: String = "") : Closeable {
    override fun close() = password.fill('\u0000')
    override fun toString() = "PhoneNasCredentials(<redacted>)"
}

/** A one-use form on a trusted LAN. No credentials, request bodies, or pairing links are logged. */
class PhoneNasPairingSession private constructor(
    private val listener: ServerSocket,
    private val now: () -> Long,
    private val allowLoopback: Boolean,
    logoBase64: String,
    private val options: PhonePairingOptions,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val received = Channel<PhoneNasCredentials>(1, onUndeliveredElement = { it.close() })
    private val token = randomToken()
    private val nonce = randomToken()
    val expiresAtMillis = now() + LIFETIME_MILLIS
    private val origin = "http://${listener.inetAddress.hostAddress}:${listener.localPort}"

    // The token stays out of HTTP paths, server logs, and referrers.
    val url = "$origin/pair#$token"
    private val page = phoneNasPage(nonce, logoBase64, options)

    init {
        listener.soTimeout = 1_000
        scope.launch {
            try {
                while (!closed.get() && now() < expiresAtMillis) {
                    val socket = try {
                        listener.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val credentials = socket.use {
                        try {
                            it.soTimeout = 3_000
                            handle(it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (credentials != null) {
                        if (!received.trySend(credentials).isSuccess) credentials.close()
                        return@launch
                    }
                }
                received.close()
            } catch (_: Exception) {
                received.close()
            } finally {
                listener.close()
            }
        }
    }

    suspend fun awaitCredentials(): PhoneNasCredentials = received.receive()

    private fun handle(socket: Socket): PhoneNasCredentials? {
        if (!(socket.inetAddress.isSiteLocalAddress || (allowLoopback && socket.inetAddress.isLoopbackAddress))) return null
        val input = socket.getInputStream()
        var remaining = 8_192
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (remaining-- > 0) {
                val value = input.read()
                require(value != -1)
                if (value == 10) return bytes.toByteArray().toString(StandardCharsets.US_ASCII).removeSuffix("\r")
                bytes += value.toByte()
            }
            error("Request headers too large")
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] in setOf("HTTP/1.1", "HTTP/1.0"))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val delimiter = header.indexOf(':')
            require(delimiter > 0)
            val key = header.substring(0, delimiter).lowercase()
            require(key !in headers)
            headers[key] = header.substring(delimiter + 1).trim()
        }
        if (now() >= expiresAtMillis || closed.get()) {
            respond(socket, 410, "This pairing link expired. Start again on the TV.")
            return null
        }
        if (headers["host"] != origin.removePrefix("http://")) {
            respond(socket, 403, "Pairing request rejected.")
            return null
        }
        if (request[0] == "GET" && request[1] == "/pair") {
            respond(socket, 200, page, "text/html; charset=utf-8")
            return null
        }
        if (request[0] != "POST" || request[1] != "/credentials") {
            respond(socket, 404, "Page not found.")
            return null
        }
        val submittedToken = headers["x-kellikanvas-pairing"].orEmpty()
        if (headers["origin"] != origin || !MessageDigest.isEqual(submittedToken.toByteArray(), token.toByteArray())) {
            respond(socket, 403, "Pairing request rejected. Scan the QR code on your TV.")
            return null
        }
        val size = headers["content-length"]?.toIntOrNull()
        if (size == null ||
            size !in 1..(if (options.needsEndpoint) 16_384 else 4_096) ||
            "transfer-encoding" in headers ||
            headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded"
        ) {
            respond(socket, 400, "Invalid login form.")
            return null
        }
        val body = input.readExactly(size)
        val credentials = try {
            val fields = body.toString(StandardCharsets.UTF_8).split('&').map { field ->
                val delimiter = field.indexOf('=')
                require(delimiter > 0)
                URLDecoder.decode(field.substring(0, delimiter), "UTF-8") to URLDecoder.decode(field.substring(delimiter + 1), "UTF-8")
            }
            val allowed = if (options.needsEndpoint) setOf("username", "password", "endpoint") else setOf("username", "password")
            require(fields.size == allowed.size && fields.map { it.first }.toSet() == allowed)
            val values = fields.toMap()
            val username = values.getValue("username").trim()
            val password = values.getValue("password")
            require(username.length in (if (options.needsUsername) 1 else 0)..128 && password.length in 1..(if (options.needsEndpoint) 4096 else 256))
            require(username.none { it.isISOControl() } && '\u0000' !in password)
            val endpoint = values["endpoint"].orEmpty().trim()
            require(endpoint.length <= 2048 && endpoint.none { it.isISOControl() })
            PhoneNasCredentials(username, password.toCharArray(), endpoint)
        } catch (_: IllegalArgumentException) {
            respond(socket, 400, "Enter a username and password.")
            return null
        } finally {
            body.fill(0)
        }
        try {
            respond(socket, 200, "Login sent. Select Connect NAS on your TV.")
        } catch (failure: Exception) {
            credentials.close()
            throw failure
        }
        return credentials
    }

    private fun respond(socket: Socket, status: Int, message: String, contentType: String = "text/plain; charset=utf-8") {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Gone"
        }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listener.close()
            received.cancel()
            scope.cancel()
        }
    }

    override fun toString() = "PhoneNasPairingSession(<redacted>)"

    companion object {
        const val LIFETIME_MILLIS = 10 * 60_000L

        fun start(address: InetAddress, logoBase64: String, options: PhonePairingOptions = PhonePairingOptions()): PhoneNasPairingSession {
            require(address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress)
            return create(address, logoBase64, System::currentTimeMillis, false, options)
        }

        internal fun forTest(now: () -> Long = System::currentTimeMillis, options: PhonePairingOptions = PhonePairingOptions()): PhoneNasPairingSession = create(InetAddress.getByName("127.0.0.1"), "", now, true, options)

        private fun create(address: InetAddress, logo: String, now: () -> Long, allowLoopback: Boolean, options: PhonePairingOptions = PhonePairingOptions()): PhoneNasPairingSession {
            val socket = ServerSocket(0, 2, address)
            return try {
                PhoneNasPairingSession(socket, now, allowLoopback, logo, options)
            } catch (failure: Exception) {
                socket.close()
                throw failure
            }
        }

        private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    try {
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            require(read > 0)
            offset += read
        }
        return bytes
    } catch (failure: Exception) {
        bytes.fill(0)
        throw failure
    }
}

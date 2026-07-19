package com.jedon.kellikanvas.source.dlna

import android.net.wifi.WifiManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SSDP_MAX_DATAGRAM_BYTES = 64 * 1024
internal const val SSDP_DISCOVERY_WINDOW_MILLIS = 3_000L

class SsdpProtocolException(message: String) : IllegalArgumentException(message)

data class SsdpDevice(
    val location: URI,
    val usn: String,
    val st: String,
    val udn: String,
)

class SsdpResponseParser {
    fun parse(
        datagram: ByteArray,
        length: Int,
        sender: InetAddress,
    ): SsdpDevice? {
        if (length !in 1..SSDP_MAX_DATAGRAM_BYTES || length > datagram.size) {
            throw SsdpProtocolException("Invalid SSDP datagram size")
        }
        val lines = datagram.decodeToString(0, length).split("\r\n")
        if (!lines.first().equals("HTTP/1.1 200 OK", ignoreCase = true)) return null
        val headerLines = lines.drop(1).takeWhile(String::isNotEmpty)
        if (headerLines.size > 64) throw SsdpProtocolException("Too many SSDP headers")
        val headers = linkedMapOf<String, String>()
        headerLines.forEach { line ->
            if (line.length > 4 * 1024) throw SsdpProtocolException("SSDP header too large")
            val separator = line.indexOf(':')
            if (separator <= 0) throw SsdpProtocolException("Malformed SSDP header")
            headers.putIfAbsent(
                line.substring(0, separator).trim().lowercase(),
                line.substring(separator + 1).trim(),
            )
        }
        val location = headers["location"] ?: return null
        val usn = headers["usn"] ?: return null
        val st = headers["st"] ?: return null
        if (!st.contains("MediaServer:1", ignoreCase = true)) return null
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (!locationMatchesSender(uri.host, sender)) return null
        val udn = usn.substringBefore("::").trim()
        if (!udn.startsWith("uuid:", ignoreCase = true) || udn.length <= 5) return null
        return SsdpDevice(uri, usn, st, udn.lowercase())
    }

    private fun locationMatchesSender(
        host: String,
        sender: InetAddress,
    ): Boolean {
        if (isIpLiteral(host)) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return literal == sender && isPrivateLanAddress(literal)
        }
        val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { return false }
        if (resolved.isEmpty() || sender !in resolved) return false
        return resolved.all(::isPrivateLanAddress)
    }

    private fun isIpLiteral(host: String): Boolean =
        IPV4_LITERAL.matches(host) || host.contains(':')

    private companion object {
        val IPV4_LITERAL = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
    }
}

interface MulticastLock {
    fun acquire()
    fun release()
}

class AndroidMulticastLock(
    wifiManager: WifiManager,
    tag: String = "KelliKanvas-DLNA",
) : MulticastLock {
    private val delegate = wifiManager.createMulticastLock(tag).apply { setReferenceCounted(false) }

    override fun acquire() = delegate.acquire()

    override fun release() {
        if (delegate.isHeld) delegate.release()
    }
}

interface SsdpTransport : AutoCloseable {
    suspend fun search(
        request: ByteArray,
        maxDatagramBytes: Int,
        windowMillis: Long,
        receive: (ByteArray, Int, InetAddress) -> Unit,
    )
}

class UdpSsdpTransport : SsdpTransport {
    private val socket = AtomicReference<DatagramSocket?>()

    override suspend fun search(
        request: ByteArray,
        maxDatagramBytes: Int,
        windowMillis: Long,
        receive: (ByteArray, Int, InetAddress) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val activeSocket = DatagramSocket()
        socket.set(activeSocket)
        continuation.invokeOnCancellation {
            socket.getAndSet(null)?.close()
        }
        thread(name = "KelliKanvas-SSDP", isDaemon = true) {
            try {
                activeSocket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        InetAddress.getByName("239.255.255.250"),
                        1900,
                    ),
                )
                val deadline = System.nanoTime() + windowMillis * 1_000_000
                val buffer = ByteArray(maxDatagramBytes)
                while (continuation.isActive) {
                    val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                    if (remainingMillis <= 0) break
                    activeSocket.soTimeout = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        activeSocket.receive(packet)
                        val source = packet.address ?: continue
                        receive(packet.data.copyOf(packet.length), packet.length, source)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                continuation.resume(Unit)
            } catch (failure: SocketException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(failure)
                }
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            } finally {
                activeSocket.close()
                socket.compareAndSet(activeSocket, null)
            }
        }
    }

    override fun close() {
        socket.getAndSet(null)?.close()
    }
}

class SsdpDiscoverer(
    private val transportFactory: () -> SsdpTransport = ::UdpSsdpTransport,
    private val multicastLock: MulticastLock,
    private val parser: SsdpResponseParser = SsdpResponseParser(),
) {
    suspend fun discover(): List<SsdpDevice> {
        val transport = transportFactory()
        multicastLock.acquire()
        return try {
            val devices = linkedMapOf<String, SsdpDevice>()
            transport.search(
                request = SEARCH_REQUEST.encodeToByteArray(),
                maxDatagramBytes = SSDP_MAX_DATAGRAM_BYTES,
                windowMillis = SSDP_DISCOVERY_WINDOW_MILLIS,
            ) { bytes, length, sender ->
                if (devices.size < MAX_DISCOVERED_DEVICES) {
                    try {
                        parser.parse(bytes, length, sender)?.let { devices.putIfAbsent(it.udn, it) }
                    } catch (_: SsdpProtocolException) {
                        // One hostile response must not abort the bounded discovery window.
                    }
                }
            }
            devices.values.toList()
        } finally {
            try {
                transport.close()
            } finally {
                multicastLock.release()
            }
        }
    }

    private companion object {
        const val MAX_DISCOVERED_DEVICES = 64

        val SEARCH_REQUEST =
            """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 2
            ST: urn:schemas-upnp-org:device:MediaServer:1

            """.trimIndent().replace("\n", "\r\n")
    }
}

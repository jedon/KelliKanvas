package com.jedon.kellikanvas.source.dlna

import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

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
        val udn = usn.substringBefore("::").trim()
        if (!udn.startsWith("uuid:", ignoreCase = true) || udn.length <= 5) return null
        return SsdpDevice(uri, usn, st, udn.lowercase())
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
        receive: (ByteArray, Int) -> Unit,
    )
}

class UdpSsdpTransport : SsdpTransport {
    @Volatile
    private var socket: DatagramSocket? = null

    override suspend fun search(
        request: ByteArray,
        maxDatagramBytes: Int,
        windowMillis: Long,
        receive: (ByteArray, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val activeSocket = DatagramSocket()
        socket = activeSocket
        val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { if (it != null) activeSocket.close() }
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
            while (currentCoroutineContext().isActive) {
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMillis <= 0) break
                activeSocket.soTimeout = remainingMillis.coerceAtMost(250).toInt().coerceAtLeast(1)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    receive(packet.data.copyOf(packet.length), packet.length)
                } catch (_: SocketTimeoutException) {
                    // Recheck cancellation and the fixed discovery deadline.
                }
            }
        } finally {
            cancellation?.dispose()
            activeSocket.close()
            socket = null
        }
    }

    override fun close() {
        socket?.close()
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
            ) { bytes, length ->
                parser.parse(bytes, length)?.let { devices.putIfAbsent(it.udn, it) }
            }
            devices.values.toList()
        } finally {
            transport.close()
            multicastLock.release()
        }
    }

    private companion object {
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

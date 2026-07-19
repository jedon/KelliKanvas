package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class SsdpDiscovererTest {
    @Test
    fun `parser accepts HTTP 200 MediaServer and extracts UDN`() {
        val response =
            response(
                "LOCATION: http://192.168.1.8:8200/root.xml",
                "USN: uuid:qnap-1::urn:schemas-upnp-org:device:MediaServer:1",
                "ST: urn:schemas-upnp-org:device:MediaServer:1",
            )

        val parsed = SsdpResponseParser().parse(response, response.size, sender("192.168.1.8"))

        assertThat(parsed!!.udn).isEqualTo("uuid:qnap-1")
        assertThat(parsed.location.toString()).isEqualTo("http://192.168.1.8:8200/root.xml")
    }

    @Test
    fun `parser rejects LOCATION whose IP does not match UDP sender`() {
        val response =
            response(
                "LOCATION: http://192.168.1.9:8200/root.xml",
                "USN: uuid:spoofed::urn:schemas-upnp-org:device:MediaServer:1",
                "ST: urn:schemas-upnp-org:device:MediaServer:1",
            )

        assertThat(SsdpResponseParser().parse(response, response.size, sender("192.168.1.8"))).isNull()
    }

    @Test
    fun `parser rejects non-200 missing fields and oversized headers`() {
        val notFound = "HTTP/1.1 404 Nope\r\n\r\n".encodeToByteArray()
        assertThat(SsdpResponseParser().parse(notFound, notFound.size, sender("192.168.1.8"))).isNull()
        assertThat(
            SsdpResponseParser().parse(
                response(
                    "LOCATION: http://192.168.1.8/root.xml",
                    "USN: uuid:qnap-1",
                ),
                response("LOCATION: http://192.168.1.8/root.xml", "USN: uuid:qnap-1").size,
                sender("192.168.1.8"),
            ),
        ).isNull()
        val huge = response("X: ${"a".repeat(4097)}", "LOCATION: http://192.168.1.8/root.xml", "USN: uuid:x", "ST: MediaServer:1")
        assertThrows(SsdpProtocolException::class.java) {
            SsdpResponseParser().parse(huge, huge.size, sender("192.168.1.8"))
        }
        val tooMany = response(*(0..64).map { "X-$it: y" }.toTypedArray())
        assertThrows(SsdpProtocolException::class.java) {
            SsdpResponseParser().parse(tooMany, tooMany.size, sender("192.168.1.8"))
        }
    }

    @Test
    fun `discover sends bounded search and deduplicates by UDN`() = runTest {
        val first = response(
            "LOCATION: http://192.168.1.8/a.xml",
            "USN: uuid:qnap::urn:schemas-upnp-org:device:MediaServer:1",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
        )
        val duplicate = response(
            "LOCATION: http://192.168.1.8/b.xml",
            "USN: uuid:qnap::urn:schemas-upnp-org:device:MediaServer:1",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
        )
        val transport = FakeTransport(
            listOf(
                FakePacket(first, sender("192.168.1.8")),
                FakePacket(duplicate, sender("192.168.1.8")),
            ),
        )
        val lock = FakeLock()

        val devices = SsdpDiscoverer({ transport }, lock).discover()

        assertThat(devices).hasSize(1)
        assertThat(transport.request).contains("HOST: 239.255.255.250:1900")
        assertThat(transport.request).contains("MAN: \"ssdp:discover\"")
        assertThat(transport.request).contains("MX: 2")
        assertThat(transport.request).contains("ST: urn:schemas-upnp-org:device:MediaServer:1")
        assertThat(transport.maxDatagramBytes).isEqualTo(64 * 1024)
        assertThat(transport.windowMillis).isEqualTo(3_000)
        assertThat(lock.acquires).isEqualTo(1)
        assertThat(lock.releases).isEqualTo(1)
        assertThat(transport.closed).isTrue()
    }

    @Test
    fun `discover accepts matching sender LOCATION and skips spoofed LOCATION`() = runTest {
        val matching = response(
            "LOCATION: http://192.168.1.8/root.xml",
            "USN: uuid:real::urn:schemas-upnp-org:device:MediaServer:1",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
        )
        val spoofed = response(
            "LOCATION: http://192.168.1.9/root.xml",
            "USN: uuid:spoof::urn:schemas-upnp-org:device:MediaServer:1",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
        )
        val afterSpoof = response(
            "LOCATION: http://192.168.1.10/root.xml",
            "USN: uuid:later::urn:schemas-upnp-org:device:MediaServer:1",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
        )
        val transport = FakeTransport(
            listOf(
                FakePacket(matching, sender("192.168.1.8")),
                FakePacket(spoofed, sender("192.168.1.8")),
                FakePacket(afterSpoof, sender("192.168.1.10")),
            ),
        )

        val devices = SsdpDiscoverer({ transport }, FakeLock()).discover()

        assertThat(devices.map(SsdpDevice::udn)).containsExactly("uuid:real", "uuid:later").inOrder()
    }

    @Test
    fun `cancellation closes transport and releases multicast lock`() = runTest {
        val transport = FakeTransport(emptyList(), stall = true)
        val lock = FakeLock()
        val job = async { SsdpDiscoverer({ transport }, lock).discover() }
        transport.started.await()

        job.cancel(CancellationException("stop discovery"))
        runCatching { job.await() }

        assertThat(transport.closed).isTrue()
        assertThat(lock.releases).isEqualTo(1)
    }

    @Test
    fun `hostile datagrams are skipped and unique devices are capped`() = runTest {
        val hostile =
            FakePacket(
                response(
                    "X: ${"x".repeat(4097)}",
                    "LOCATION: http://192.168.1.8/root.xml",
                    "USN: uuid:hostile",
                    "ST: urn:schemas-upnp-org:device:MediaServer:1",
                ),
                sender("192.168.1.8"),
            )
        val valid =
            (0 until 100).map { index ->
                val host = "192.168.1.${index + 1}"
                FakePacket(
                    response(
                        "LOCATION: http://$host/root.xml",
                        "USN: uuid:server-$index",
                        "ST: urn:schemas-upnp-org:device:MediaServer:1",
                    ),
                    sender(host),
                )
            }
        val lock = FakeLock()

        val devices = SsdpDiscoverer({ FakeTransport(listOf(hostile) + valid) }, lock).discover()

        assertThat(devices).hasSize(64)
        assertThat(devices.map(SsdpDevice::udn)).doesNotContain("uuid:hostile")
        assertThat(lock.releases).isEqualTo(1)
    }

    @Test
    fun `multicast lock releases when transport close throws`() = runTest {
        val lock = FakeLock()
        val transport = FakeTransport(emptyList(), closeFailure = IllegalStateException("close"))

        val failure = runCatching { SsdpDiscoverer({ transport }, lock).discover() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(lock.releases).isEqualTo(1)
    }

    private fun response(vararg headers: String): ByteArray = (
        "HTTP/1.1 200 OK\r\n" + headers.joinToString("\r\n") + "\r\n\r\n"
        ).encodeToByteArray()

    private fun sender(host: String): InetAddress = InetAddress.getByName(host)

    private class FakeLock : MulticastLock {
        var acquires = 0
        var releases = 0
        override fun acquire() {
            acquires++
        }

        override fun release() {
            releases++
        }
    }

    private data class FakePacket(
        val bytes: ByteArray,
        val sender: InetAddress,
    )

    private class FakeTransport(
        private val packets: List<FakePacket>,
        private val stall: Boolean = false,
        private val closeFailure: Throwable? = null,
    ) : SsdpTransport {
        val started = CompletableDeferred<Unit>()
        var request = ""
        var maxDatagramBytes = 0
        var windowMillis = 0L
        var closed = false

        override suspend fun search(
            request: ByteArray,
            maxDatagramBytes: Int,
            windowMillis: Long,
            receive: (ByteArray, Int, InetAddress) -> Unit,
        ) {
            this.request = request.decodeToString()
            this.maxDatagramBytes = maxDatagramBytes
            this.windowMillis = windowMillis
            started.complete(Unit)
            packets.forEach { receive(it.bytes, it.bytes.size, it.sender) }
            if (stall) CompletableDeferred<Unit>().await()
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}

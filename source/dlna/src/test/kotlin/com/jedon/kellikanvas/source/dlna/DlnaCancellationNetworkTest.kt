package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class DlnaCancellationNetworkTest {
    @Test
    fun `ContentDirectory cancellation promptly cancels a real call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = ContentDirectoryClient(OkHttpClient(), server.url("/control").toUri(), "uuid:qnap", 1)
            val request = async(Dispatchers.IO) { client.browseDirectChildren("0", 0, 1) }
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()

            request.cancel(CancellationException("cancel SOAP"))

            withTimeout(1_000) {
                runCatching { request.await() }
            }
            assertThat(request.isCancelled).isTrue()
        }
    }

    @Test
    fun `ContentDirectory cancellation closes a blocking response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("x".repeat(1_000))
                    .setBodyDelay(10, TimeUnit.SECONDS),
            )
            val client = ContentDirectoryClient(OkHttpClient(), server.url("/control").toUri(), "uuid:qnap", 1)
            val request = async(Dispatchers.IO) { client.browseDirectChildren("0", 0, 1) }
            delay(300)
            assertThat(server.requestCount).isEqualTo(1)

            request.cancel(CancellationException("cancel body"))

            withTimeout(1_000) {
                runCatching { request.await() }
            }
            assertThat(request.isCancelled).isTrue()
        }
    }

    @Test
    fun `photo loading bypasses configured HTTP proxy`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("photo"))
            val proxiedClient =
                OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
                    .build()
            val policy = DlnaEndpointPolicy(server.url("/root.xml").toUri()) { it.isLoopbackAddress }
            val loader = DlnaPhotoLoader(proxiedClient, policy)

            val stream = loader.open(server.url("/photo").toUri())
            val sink = Buffer()
            stream.use {
                while (it.read(sink, 8) != -1L) Unit
            }

            assertThat(sink.readUtf8()).isEqualTo("photo")
        }
    }

    @Test
    fun `real UDP search cancellation closes blocking receive promptly`() = runBlocking {
        val transport = UdpSsdpTransport()
        val request =
            async(Dispatchers.IO) {
                transport.search(
                    "M-SEARCH * HTTP/1.1\r\n\r\n".encodeToByteArray(),
                    SSDP_MAX_DATAGRAM_BYTES,
                    30_000,
                ) { _, _ -> }
            }
        delay(100)

        request.cancel(CancellationException("cancel receive"))

        withTimeout(1_000) {
            runCatching { request.await() }
        }
        assertThat(request.isCancelled).isTrue()
    }
}

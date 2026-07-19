package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class DlnaCancellationNetworkTest {
    @Test
    fun `ContentDirectory cancellation promptly cancels a real call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val endpoint = server.url("/control").newBuilder().host("127.0.0.1").build().toUri()
            val client = ContentDirectoryClient(OkHttpClient(), endpoint, "uuid:qnap", 1)
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
        val source = BlockingSource()
        val call = CancellingCall(source::close)
        val request =
            async(Dispatchers.IO) {
                call.readBoundedCancellable(source.buffer(), 1_000, "test body")
            }
        assertThat(source.started.await(5, TimeUnit.SECONDS)).isTrue()

        request.cancel(CancellationException("cancel body"))

        withTimeout(1_000) {
            runCatching { request.await() }
        }
        assertThat(request.isCancelled).isTrue()
        assertThat(call.isCanceled()).isTrue()
        assertThat(source.closed.get()).isTrue()
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
                ) { _, _, _ -> }
            }
        delay(100)

        request.cancel(CancellationException("cancel receive"))

        withTimeout(1_000) {
            runCatching { request.await() }
        }
        assertThat(request.isCancelled).isTrue()
    }
}

private class BlockingSource : Source {
    val started = CountDownLatch(1)
    val closed = AtomicBoolean()
    private val released = CountDownLatch(1)

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        started.countDown()
        released.await()
        return -1
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed.set(true)
        released.countDown()
    }
}

private class CancellingCall(
    private val onCancel: () -> Unit,
) : Call {
    private val canceled = AtomicBoolean()

    override fun request(): Request = Request.Builder().url("http://127.0.0.1/").build()

    override fun execute(): Response = throw UnsupportedOperationException()

    override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()

    override fun cancel() {
        canceled.set(true)
        onCancel()
    }

    override fun isExecuted(): Boolean = false

    override fun isCanceled(): Boolean = canceled.get()

    override fun timeout(): Timeout = Timeout.NONE

    override fun addEventListener(eventListener: EventListener) = Unit

    override fun <T : Any> tag(type: KClass<T>): T? = null

    override fun <T> tag(type: Class<out T>): T? = null

    override fun <T : Any> tag(
        type: KClass<T>,
        computeIfAbsent: () -> T,
    ): T = computeIfAbsent()

    override fun <T : Any> tag(
        type: Class<T>,
        computeIfAbsent: () -> T,
    ): T = computeIfAbsent()

    override fun clone(): Call = CancellingCall(onCancel)
}

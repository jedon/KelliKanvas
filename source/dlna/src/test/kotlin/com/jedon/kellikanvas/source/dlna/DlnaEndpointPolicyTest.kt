package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class DlnaEndpointPolicyTest {
    @Test
    fun `allows discovered private HTTP endpoint and rejects unsafe destinations`() {
        val policy = DlnaEndpointPolicy(URI("http://192.168.1.8:8200/root.xml"))

        policy.validateInitial(URI("http://192.168.1.8:8200/photo.jpg"))
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateInitial(URI("http://8.8.8.8/photo.jpg"))
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateInitial(URI("http://user:pass@192.168.1.8/photo.jpg"))
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateInitial(URI("ftp://192.168.1.8/photo.jpg"))
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateInitial(URI("http://239.255.255.250/photo.jpg"))
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateInitial(URI("http://0.0.0.0/photo.jpg"))
        }
    }

    @Test
    fun `redirects cannot cross security or leave discovered endpoint`() {
        val policy = DlnaEndpointPolicy(URI("http://192.168.1.8:8200/root.xml"))
        val from = URI("http://192.168.1.8:8200/photo.jpg")

        assertThrows(DlnaSecurityException::class.java) {
            policy.validateRedirect(from, URI("https://192.168.1.8/photo.jpg"), 1)
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateRedirect(from, URI("http://192.168.1.9/photo.jpg"), 1)
        }
        assertThrows(DlnaSecurityException::class.java) {
            policy.validateRedirect(from, URI("http://192.168.1.8:8200/photo.jpg"), 4)
        }
    }

    @Test
    fun `photo request follows at most three validated redirects without auth`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/two"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("photo"))
            val location = server.url("/root.xml").toUri()
            val policy = DlnaEndpointPolicy(location) { it.isLoopbackAddress }
            val loader = DlnaPhotoLoader(OkHttpClient(), policy)

            val stream = loader.open(server.url("/one").toUri())
            val sink = okio.Buffer()
            while (stream.read(sink, 2) != -1L) Unit
            stream.close()

            assertThat(sink.readUtf8()).isEqualTo("photo")
            assertThat(server.takeRequest().headers["Authorization"]).isNull()
            assertThat(server.takeRequest().headers["Authorization"]).isNull()
        }
    }
}

package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

class PhoneNasPairingSessionTest {
    @Test fun `connector setup receives a server URL and long API key without requiring username`() = runBlocking {
        PhoneNasPairingSession.forTest(options = PhonePairingOptions(title = "Immich", needsEndpoint = true, needsUsername = false)).use { session ->
            val body = "username=&password=" + "a".repeat(1024) + "&endpoint=" + URLEncoder.encode("https://photos.example/immich/", "UTF-8")
            assertThat(request(session, body)).startsWith("HTTP/1.1 200")
            withTimeout(2_000) { session.awaitCredentials() }.use { login ->
                assertThat(login.endpoint).isEqualTo("https://photos.example/immich/")
                assertThat(login.username).isEmpty()
                assertThat(login.password).hasLength(1024)
            }
        }
    }

    @Test fun `one use phone submission preserves punctuation and wipes the consumed password`() = runBlocking {
        PhoneNasPairingSession.forTest().use { session ->
            val page = request(session)
            assertThat(page).startsWith("HTTP/1.1 200")
            assertThat(page).contains("Cache-Control: no-store")
            assertThat(page).contains("frame-ancestors 'none'")
            assertThat(page).doesNotContain(URI(session.url).fragment)
            val password = " fixture! &+%pass "
            val body = "username=" + URLEncoder.encode("Frame user", "UTF-8") + "&password=" + URLEncoder.encode(password, "UTF-8")
            assertThat(request(session, body = body)).startsWith("HTTP/1.1 200")
            val credentials = withTimeout(2_000) { session.awaitCredentials() }
            assertThat(credentials.username).isEqualTo("Frame user")
            assertThat(String(credentials.password)).isEqualTo(password)
            assertThat(credentials.toString()).doesNotContain(password)
            assertThat(session.toString()).doesNotContain(URI(session.url).fragment)
            credentials.close()
            assertThat(credentials.password.all { it == '\u0000' }).isTrue()
            assertThat(runCatching { request(session, body = body) }.isFailure).isTrue()
        }
    }

    @Test fun `wrong token cross origin and rebinding requests cannot submit a login`() = runBlocking {
        PhoneNasPairingSession.forTest().use { session ->
            val body = "username=fixture&password=fixture"
            assertThat(request(session, body, token = "wrong")).startsWith("HTTP/1.1 403")
            assertThat(request(session, body, origin = "http://evil.example")).startsWith("HTTP/1.1 403")
            assertThat(request(session, body, host = "evil.example")).startsWith("HTTP/1.1 403")
            assertThat(request(session, body)).startsWith("HTTP/1.1 200")
            withTimeout(2_000) { session.awaitCredentials() }.close()
        }
    }

    @Test fun `expired link rejects a submission and cancellation closes the listener`() {
        val clock = AtomicLong(1_000)
        PhoneNasPairingSession.forTest(clock::get).use { session ->
            assertThat(request(session)).startsWith("HTTP/1.1 200")
            clock.set(session.expiresAtMillis)
            assertThat(request(session, body = "username=fixture&password=fixture")).startsWith("HTTP/1.1 410")
        }
        val session = PhoneNasPairingSession.forTest()
        session.close()
        assertThat(runCatching { request(session) }.isFailure).isTrue()
    }

    @Test fun `invalid duplicate and oversized forms do not consume the pairing session`() = runBlocking {
        PhoneNasPairingSession.forTest().use { session ->
            assertThat(request(session, "username=fixture&password=")).startsWith("HTTP/1.1 400")
            assertThat(request(session, "username=fixture&username=other&password=fixture")).startsWith("HTTP/1.1 400")
            assertThat(request(session, "username=fixture&password=fixture&unexpected=value")).startsWith("HTTP/1.1 400")
            assertThat(request(session, "", declaredLength = 5_000)).startsWith("HTTP/1.1 400")
            assertThat(request(session, "username=fixture&password=fixture")).startsWith("HTTP/1.1 200")
            withTimeout(2_000) { session.awaitCredentials() }.close()
        }
    }

    private fun request(
        session: PhoneNasPairingSession,
        body: String? = null,
        token: String = URI(session.url).fragment,
        origin: String = "http://${URI(session.url).authority}",
        host: String = URI(session.url).authority,
        declaredLength: Int? = null,
    ): String {
        val uri = URI(session.url)
        val encoded = body?.toByteArray(Charsets.UTF_8)
        val headers = if (body != null) {
            "POST /credentials HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\nX-KelliKanvas-Pairing: $token\r\n" +
                "Content-Type: application/x-www-form-urlencoded;charset=UTF-8\r\nContent-Length: ${declaredLength ?: encoded!!.size}\r\n\r\n"
        } else {
            "GET /pair HTTP/1.1\r\nHost: $host\r\n\r\n"
        }
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.US_ASCII))
                encoded?.let(::write)
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}

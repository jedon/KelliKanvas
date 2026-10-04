package com.jedon.kellikanvas.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class ImmichIntegrationTest {
    @Test fun loginCreatesOnlyPhotoReadKeyAndRevokesTemporarySession() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"accessToken":"fixture-session","shouldChangePassword":false}"""))
            server.enqueue(MockResponse().setBody("""{"secret":"fixture-integration-key"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val integration = ImmichIntegration(OkHttpClient()) { emptyList() }
            val result = integration.execute(Json.parseToJsonElement("""{"id":"fixture-job","operation":"login","endpoint":"${server.url("/")}","username":"kelli@example.test","secret":"fixture-password"}""").jsonObject)
            assertThat(result.getValue("secret").jsonPrimitive.content).isEqualTo("fixture-integration-key")
            assertThat(result.toString()).doesNotContain("fixture-password")
            assertThat(result.toString()).doesNotContain("fixture-session")
            val login = server.takeRequest()
            assertThat(login.path).isEqualTo("/api/auth/login")
            assertThat(login.getHeader("Authorization")).isNull()
            val creation = server.takeRequest()
            assertThat(creation.path).isEqualTo("/api/api-keys")
            assertThat(creation.getHeader("Authorization")).isEqualTo("Bearer fixture-session")
            val permissions = Json.parseToJsonElement(creation.body.readUtf8()).jsonObject.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }
            assertThat(permissions).containsExactly("album.read", "asset.read", "asset.download", "asset.view")
            assertThat(server.takeRequest().path).isEqualTo("/api/auth/logout")
        }
    }

    @Test fun forcedPasswordChangeDoesNotCreateIntegrationKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"accessToken":"fixture-session","shouldChangePassword":true}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val result = ImmichIntegration(OkHttpClient()) { emptyList() }.execute(Json.parseToJsonElement("""{"operation":"login","endpoint":"${server.url("/")}","username":"kelli@example.test","secret":"fixture-password"}""").jsonObject)
            assertThat(result.getValue("error").jsonPrimitive.content).isEqualTo("password_change")
            assertThat(result["secret"]).isNull()
            assertThat(server.takeRequest().path).isEqualTo("/api/auth/login")
            assertThat(server.takeRequest().path).isEqualTo("/api/auth/logout")
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test fun localDiscoveryNeverExpandsPublicOrCarrierNetworks() {
        assertThat(LocalImmichDiscovery.subnetCandidates("192.168.7.32")).hasSize(254)
        assertThat(LocalImmichDiscovery.subnetCandidates("192.168.7.32")).contains("192.168.7.216")
        for (address in listOf("100.64.1.2", "8.8.8.8", "127.0.0.1", "192.168.999.2", "::1")) {
            assertThat(LocalImmichDiscovery.subnetCandidates(address)).isEmpty()
        }
    }
}

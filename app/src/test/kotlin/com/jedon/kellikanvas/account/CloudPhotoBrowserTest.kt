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

class CloudPhotoBrowserTest {
    @Test fun immichAlbumsAndPagingUsePrivateCredentialsAndReturnOnlyMetadata() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val browser = CloudPhotoBrowser(OkHttpClient())
            val albums = (1..205).joinToString(",") { """{"id":"$it","albumName":"Album $it"}""" }
            val work = Json.parseToJsonElement("""{"id":"fixture","connection":{"id":"fixture-source","name":"Immich","provider":"IMMICH","configuration":{"endpoint":"${server.url("/")}","username":"","secret":"fixture-private-key"}},"objectId":null,"cursor":null}""").jsonObject
            server.enqueue(MockResponse().setBody("[$albums]"))
            val first = browser.list(work)
            assertThat(first["folders"]!!.jsonArray.size).isEqualTo(200)
            assertThat(first.toString()).doesNotContain("fixture-private-key")
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/albums")
            assertThat(request.getHeader("x-api-key")).isEqualTo("fixture-private-key")
            server.enqueue(MockResponse().setBody("[$albums]"))
            val second = browser.list(kotlinx.serialization.json.JsonObject(work + mapOf("objectId" to kotlinx.serialization.json.JsonPrimitive("root"), "cursor" to first.getValue("nextCursor"))))
            assertThat(second["folders"]!!.jsonArray.size).isEqualTo(6)
            assertThat(second["folders"]!!.jsonArray.first().jsonObject["objectId"]!!.jsonPrimitive.content).isEqualTo("album:200")
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret provider response fixture-private-key"))
            val failed = browser.list(work)
            assertThat(failed["error"]!!.jsonPrimitive.content).isEqualTo("authentication")
            assertThat(failed.toString()).doesNotContain("fixture-private-key")
        } finally {
            server.shutdown()
        }
    }
}

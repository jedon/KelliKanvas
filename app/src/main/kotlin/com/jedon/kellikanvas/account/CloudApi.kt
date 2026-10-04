package com.jedon.kellikanvas.account

import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.connected.ConnectorHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

internal val ACCOUNT_VAULT_ID = SourceProfileId("kanvas-account-v1")
internal class CloudPairing(val id: String, val secret: String, val code: String, val uri: String, val expiresAt: Long, val interval: Long) {
    override fun toString() = "CloudPairing(<redacted>)"
}

internal class CloudApi(origin: String, client: OkHttpClient, private val token: () -> String? = { null }) {
    val origin: HttpUrl = validatedCloudOrigin(origin)
    private val http = ConnectorHttp(ACCOUNT_VAULT_ID, client, { token()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap() }, this.origin)
    private fun url(path: String) = requireNotNull(origin.resolve(path))
    suspend fun start(name: String): CloudPairing {
        val response = http.json(url("api/pairings"), "POST", buildJsonObject { put("name", name.take(80)) }.body()).jsonObject
        val uri = response.getValue("verificationUri").jsonPrimitive.content
        val target = uri.toHttpUrl()
        require(target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port && target.encodedPath == "/pair") { "The server returned an invalid pairing address" }
        val code = response.getValue("userCode").jsonPrimitive.content
        require(code.matches(Regex("[A-Z2-9]{8}")) && target.queryParameter("code") == code)
        return CloudPairing(
            response.getValue("id").jsonPrimitive.content,
            response.getValue("deviceSecret").jsonPrimitive.content,
            code,
            uri,
            System.currentTimeMillis() + response.getValue("expiresIn").jsonPrimitive.long.coerceIn(1, 600) * 1000,
            response.getValue("interval").jsonPrimitive.long.coerceIn(5, 30) * 1000,
        )
    }
    suspend fun poll(pair: CloudPairing): JsonObject = http.json(url("api/pairings/${pair.id}/poll"), "POST", buildJsonObject { put("deviceSecret", pair.secret) }.body()).jsonObject
    suspend fun cancel(pair: CloudPairing) {
        http.open(url("api/pairings/${pair.id}/cancel"), "POST", buildJsonObject { put("deviceSecret", pair.secret) }.body()).close()
    }
    suspend fun state(): JsonObject = http.json(url("api/device/state")).jsonObject
    suspend fun nextBrowse(): JsonObject? = http.text(url("api/device/browse/next"), "POST", buildJsonObject {}.body()).takeIf { it.isNotBlank() }?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
    suspend fun completeBrowse(id: String, result: JsonObject) {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        http.open(url("api/device/browse/$id/complete"), "POST", result.body()).close()
    }
    suspend fun save(revision: Long, settings: JsonObject, connections: JsonArray) {
        http.open(
            url("api/device/state"),
            "PUT",
            buildJsonObject {
                put("expectedRevision", revision)
                put("settings", settings)
                put("connections", connections)
            }.body(),
        ).close()
    }
    suspend fun logout() {
        http.open(url("api/device/logout"), "POST", "{}".toRequestBody("application/json".toMediaType())).close()
    }
}
internal fun JsonObject.body() = toString().toRequestBody("application/json".toMediaType())
internal fun validatedCloudOrigin(raw: String): HttpUrl = (raw.trim().trimEnd('/') + "/").toHttpUrl().also {
    require(it.isHttps || it.host in listOf("localhost", "127.0.0.1", "::1")) { "Use an HTTPS account server" }
    require(it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) { "Enter only the account server's HTTPS origin" }
}

package com.jedon.kellikanvas.source.google

import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class GoogleTvConfiguration(val clientId: String, val clientSecret: String) {
    val configured get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    override fun toString() = "GoogleTvConfiguration(<redacted>)"
}

class GoogleDeviceChallenge(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long,
    val expiresAtMillis: Long,
    val requestId: String? = null,
    val verificationUrlComplete: String? = null,
) {
    val signInUrl get() = verificationUrlComplete ?: verificationUrl
    override fun toString() = "GoogleDeviceChallenge(<redacted>)"
}

/** Official TV device authorization; never use this flow with Drive's readonly scope. */
class GooglePhotosOAuth(
    private val id: SourceProfileId,
    private val config: GoogleTvConfiguration,
    private val client: OkHttpClient,
    private val endpoint: HttpUrl = "https://oauth2.googleapis.com/".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun begin(requestId: String = UUID.randomUUID().toString()): GoogleDeviceChallenge = safeGoogleOperation(id, "connect") {
        check(config.configured) { "Google Photos is not configured" }
        require(requestId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        val state = JsonObject(mapOf("requestId" to JsonPrimitive(requestId), "displayName" to JsonPrimitive("KelliKanvas")))
        val response = form("device/code", mapOf("client_id" to config.clientId, "scope" to GOOGLE_PHOTOS_SCOPE, "state" to state.toString()))
        val url = response.text("verification_url").ifBlank { response.text("verification_uri") }
        requireGoogleLink(url)
        val complete = response.text("verification_url_complete").ifBlank { response.text("verification_uri_complete") }.takeIf { it.isNotBlank() }
        complete?.let(::requireGoogleLink)
        GoogleDeviceChallenge(
            response.text("device_code").also { require(it.isNotBlank()) },
            response.text("user_code").also { require(it.isNotBlank()) },
            url,
            response["interval"]?.jsonPrimitive?.longOrNull?.coerceIn(5, 60) ?: 5,
            now() + (response["expires_in"]?.jsonPrimitive?.longOrNull?.coerceIn(1, 1800) ?: 600) * 1_000,
            requestId,
            complete,
        )
    }

    suspend fun awaitGrant(challenge: GoogleDeviceChallenge): GoogleGrant {
        var interval = challenge.intervalSeconds
        while (now() < challenge.expiresAtMillis) {
            delay(interval * 1_000)
            if (now() >= challenge.expiresAtMillis) break
            val response = form(
                "token",
                mapOf(
                    "client_id" to config.clientId,
                    "client_secret" to config.clientSecret,
                    "device_code" to challenge.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
                allowPending = true,
            )
            when (response.text("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval = (interval + 5).coerceAtMost(120)
                    continue
                }
                "" -> return grant(response)
                else -> throw SourceFailure.AuthenticationRequired(id, "connect", "Google sign-in was declined or expired. Start again")
            }
        }
        throw SourceFailure.AuthenticationRequired(id, "connect", "This sign-in code expired. Start again")
    }

    suspend fun refresh(previous: GoogleGrant): GoogleGrant {
        if (previous.clientId != config.clientId || previous.refreshToken.isBlank()) {
            throw SourceFailure.AuthenticationRequired(id, "connect", "Reconnect Google Photos")
        }
        return grant(
            form(
                "token",
                mapOf(
                    "client_id" to config.clientId,
                    "client_secret" to config.clientSecret,
                    "refresh_token" to previous.refreshToken,
                    "grant_type" to "refresh_token",
                ),
            ),
            previous.refreshToken,
        )
    }

    private suspend fun form(path: String, fields: Map<String, String>, allowPending: Boolean = false): JsonObject = safeGoogleOperation(id, "connect") {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val call = client.newCall(Request.Builder().url(endpoint.resolve(path)!!).post(body).build())
        call.awaitGoogleResponse().use { response ->
            val source = response.body.source()
            // A grant response is tiny. Fail closed rather than logging an unexpected body.
            val bytes = call.withGoogleCancellation {
                val buffer = okio.Buffer()
                while (buffer.size <= 16_384) {
                    if (source.read(buffer, minOf(8192L, 16_385 - buffer.size)) == -1L) break
                }
                require(buffer.size <= 16_384) { "Oversized Google response" }
                buffer.readByteArray()
            }
            val json = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw IllegalArgumentException("Invalid Google response")
            } finally {
                bytes.fill(0)
            }
            if (!response.isSuccessful && !(allowPending && json.text("error") in setOf("authorization_pending", "slow_down", "access_denied", "expired_token"))) {
                throw SourceFailure.AuthenticationRequired(id, "connect", "Google sign-in could not be completed. Check the Google connection configuration")
            }
            json
        }
    }

    private fun grant(json: JsonObject, previousRefresh: String = ""): GoogleGrant {
        val token = json.text("access_token")
        val refresh = json.text("refresh_token").ifBlank { previousRefresh }
        val scope = json.text("scope")
        if (token.isBlank() || refresh.isBlank() || (scope.isNotBlank() && GOOGLE_PHOTOS_SCOPE !in scope.split(' '))) {
            throw SourceFailure.AuthenticationRequired(id, "connect", "Google Photos permission is required")
        }
        return GoogleGrant(
            token,
            now() + (json["expires_in"]?.jsonPrimitive?.longOrNull?.coerceIn(1, 86_400) ?: 3600) * 1_000,
            refreshToken = refresh,
            clientId = config.clientId,
        )
    }
}

fun requireGoogleLink(value: String): HttpUrl {
    val url = value.toHttpUrl()
    require(url.isHttps && (url.host == "google.com" || url.host.endsWith(".google.com"))) { "Invalid Google link" }
    return url
}

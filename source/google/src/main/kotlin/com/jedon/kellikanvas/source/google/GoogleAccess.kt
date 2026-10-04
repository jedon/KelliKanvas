package com.jedon.kellikanvas.source.google

import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val GOOGLE_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
const val GOOGLE_PHOTOS_SCOPE = "https://www.googleapis.com/auth/photosambient.mediaitems"

fun interface GoogleAccess {
    suspend fun token(forceRefresh: Boolean): String
}

/** Never place grants in the catalog, logs, saved instance state, or plaintext preferences. */
class GoogleGrant(
    val accessToken: String,
    val expiresAtMillis: Long,
    val refreshToken: String = "",
    val accountName: String = "",
    val clientId: String = "",
) {
    override fun toString(): String = "GoogleGrant(<redacted>)"
}

class GoogleGrantStore(private val vault: CredentialVault) {
    fun read(id: SourceProfileId): GoogleGrant? {
        val result = vault.read(id)
        if (result !is CredentialReadResult.Present) return null
        return result.use {
            val bytes = it.secret.copyBytes()
            try {
                val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
                GoogleGrant(
                    json.text("accessToken"),
                    json["expiresAtMillis"]?.jsonPrimitive?.longOrNull ?: 0,
                    json.text("refreshToken"),
                    json.text("accountName"),
                    json.text("clientId"),
                ).takeIf { grant -> grant.accessToken.isNotBlank() }
            } catch (_: Exception) {
                null
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun write(id: SourceProfileId, grant: GoogleGrant) {
        val bytes = JsonObject(
            mapOf(
                "accessToken" to JsonPrimitive(grant.accessToken),
                "expiresAtMillis" to JsonPrimitive(grant.expiresAtMillis),
                "refreshToken" to JsonPrimitive(grant.refreshToken),
                "accountName" to JsonPrimitive(grant.accountName),
                "clientId" to JsonPrimitive(grant.clientId),
            ),
        ).toString().toByteArray()
        try {
            vault.write(id, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun remove(id: SourceProfileId) = vault.remove(id)
}

/** Refresh is serialized per profile so preview and playback cannot race a renewal. */
class StoredGoogleAccess(
    private val id: SourceProfileId,
    private val store: GoogleGrantStore,
    private val refresh: suspend (GoogleGrant) -> GoogleGrant,
    private val now: () -> Long = System::currentTimeMillis,
) : GoogleAccess {
    private val mutex = Mutex()
    override suspend fun token(forceRefresh: Boolean): String = mutex.withLock {
        val grant = store.read(id) ?: throw SourceFailure.AuthenticationRequired(id, "connect", "Reconnect your Google account")
        if (!forceRefresh && grant.expiresAtMillis > now() + 60_000) return@withLock grant.accessToken
        val renewed = refresh(grant)
        store.write(id, renewed)
        renewed.accessToken
    }
}

internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

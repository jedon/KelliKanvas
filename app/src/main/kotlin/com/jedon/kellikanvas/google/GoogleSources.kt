package com.jedon.kellikanvas.google

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.google.GOOGLE_DRIVE_SCOPE
import com.jedon.kellikanvas.source.google.GoogleAccess
import com.jedon.kellikanvas.source.google.GoogleDriveSourceAdapter
import com.jedon.kellikanvas.source.google.GoogleGrant
import com.jedon.kellikanvas.source.google.GoogleGrantStore
import com.jedon.kellikanvas.source.google.GoogleHttp
import com.jedon.kellikanvas.source.google.GooglePhotosOAuth
import com.jedon.kellikanvas.source.google.GooglePhotosSourceAdapter
import com.jedon.kellikanvas.source.google.GoogleTvConfiguration
import com.jedon.kellikanvas.source.google.StoredGoogleAccess
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleSources(
    private val context: Context,
    private val client: OkHttpClient,
    vault: CredentialVault,
    val photosConfiguration: GoogleTvConfiguration,
) {
    val grants = GoogleGrantStore(vault)
    private val adapters = LinkedHashMap<GoogleConnection, SourceAdapter>()

    fun driveAuthorization(id: SourceProfileId): Task<AuthorizationResult> {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GOOGLE_DRIVE_SCOPE)))
        grants.read(id)?.accountName?.takeIf { it.isNotBlank() }?.let { request.setAccount(Account(it, "com.google")) }
        return Identity.getAuthorizationClient(context).authorize(request.build())
    }

    suspend fun acceptDrive(id: SourceProfileId, result: AuthorizationResult): GoogleDriveSourceAdapter {
        val token = result.accessToken
        if (token.isNullOrBlank() || GOOGLE_DRIVE_SCOPE !in result.grantedScopes) {
            throw SourceFailure.AuthenticationRequired(id, "connect", "Google Drive permission is required")
        }
        val temporary = GoogleDriveSourceAdapter(id, GoogleHttp(id, client, GoogleAccess { token }))
        val account = temporary.accountName()
        val existingAccount = grants.read(id)?.accountName
        if (!existingAccount.isNullOrBlank() && existingAccount != account) {
            throw SourceFailure.AuthenticationRequired(id, "connect", "Reconnect the original Google account, or add a new source")
        }
        grants.write(id, GoogleGrant(token, System.currentTimeMillis() + 3_600_000, accountName = account))
        return drive(id)
    }

    fun drive(id: SourceProfileId): GoogleDriveSourceAdapter = GoogleDriveSourceAdapter(
        id,
        GoogleHttp(
            id,
            client,
            StoredGoogleAccess(id, grants, refresh = { previous ->
                if (previous.accountName.isBlank()) throw SourceFailure.AuthenticationRequired(id, "connect", "Reconnect Google Drive")
                val authorization = Identity.getAuthorizationClient(context)
                authorization.clearToken(ClearTokenRequest.builder().setToken(previous.accessToken).build()).awaitGoogleTask()
                val result = driveAuthorization(id).awaitGoogleTask()
                if (result.hasResolution() || result.accessToken.isNullOrBlank() || GOOGLE_DRIVE_SCOPE !in result.grantedScopes) {
                    throw SourceFailure.AuthenticationRequired(id, "connect", "Reconnect Google Drive from Collection")
                }
                GoogleGrant(result.accessToken!!, System.currentTimeMillis() + 3_600_000, accountName = previous.accountName)
            }),
        ),
    )

    fun photosOAuth(id: SourceProfileId) = GooglePhotosOAuth(id, photosConfiguration, client)
    fun photosHttp(id: SourceProfileId) = GoogleHttp(id, client, StoredGoogleAccess(id, grants, photosOAuth(id)::refresh))

    fun restore(connection: GoogleConnection, kind: SourceKind): SourceAdapter? {
        if (grants.read(connection.profileId) == null) return null
        if (kind == SourceKind.GOOGLE_PHOTOS && (!photosConfiguration.configured || connection.clientId != photosConfiguration.clientId)) return null
        return synchronized(adapters) {
            adapters.getOrPut(connection) {
                when (kind) {
                    SourceKind.GOOGLE_DRIVE -> drive(connection.profileId)
                    SourceKind.GOOGLE_PHOTOS -> GooglePhotosSourceAdapter(connection.profileId, connection.resourceId, photosHttp(connection.profileId))
                    else -> error("Not a Google source")
                }
            }.also { while (adapters.size > 16) adapters.remove(adapters.keys.first()) }
        }
    }

    suspend fun disconnect(connection: GoogleConnection) {
        try {
            if (connection.resourceId.isNotBlank() && connection.clientId == photosConfiguration.clientId) {
                GooglePhotosSourceAdapter(connection.profileId, connection.resourceId, photosHttp(connection.profileId)).deleteDevice()
            }
        } finally {
            synchronized(adapters) { adapters.remove(connection) }
            grants.remove(connection.profileId)
        }
    }
}

internal suspend fun <T> Task<T>.awaitGoogleTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

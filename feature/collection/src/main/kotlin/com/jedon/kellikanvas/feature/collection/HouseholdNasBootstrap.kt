package com.jedon.kellikanvas.feature.collection

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Zero-friction first-launch connect: household SMB photo roots, then DLNA Photos.
 * Successful sources are merged (each save retains other profiles' roots).
 */
class HouseholdNasBootstrap(
    private val smb: SmbSetupController,
    private val dlna: DlnaSetupController,
    private val hasHouseholdSmbCredentials: () -> Boolean,
) {
    suspend fun ensurePhotosCollection(): BootstrapResult {
        val added = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (hasHouseholdSmbCredentials()) {
            try {
                val smbResult = smb.connectHousehold()
                added += "SMB ${smbResult.share} (${smbResult.rootCount} folders)"
                Log.i(TAG, "Household SMB connected host=${smbResult.host} roots=${smbResult.rootCount}")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w(TAG, "Household SMB bootstrap failed", failure)
                errors += failure.message?.take(120) ?: "SMB connect failed"
            }
        } else {
            Log.i(TAG, "Household SMB credentials not baked in; skipping SMB bootstrap")
        }

        try {
            val server = dlna.tryKnownHosts()
            val folders = dlna.listChildren(server.profile, folderObjectId = "0")
            val photos = PhotosFolderPicker.selectedPhotosFolder(folders)
            if (photos != null) {
                dlna.saveSelection(
                    profile = server.profile,
                    friendlyName = server.friendlyName,
                    folders = listOf(photos),
                )
                added += "DLNA Photos"
                Log.i(TAG, "DLNA Photos auto-selected via ${server.matchedHost ?: server.friendlyName}")
            } else {
                errors += "DLNA connected but no Photos folder at root"
                Log.w(TAG, "DLNA root has no Photos folder; titles=${folders.map { it.title }}")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(TAG, "DLNA Photos bootstrap failed", failure)
            errors += failure.message?.take(120) ?: "DLNA connect failed"
        }

        return when {
            added.isNotEmpty() ->
                BootstrapResult.Success(sources = added.toList(), warnings = errors.toList())
            else ->
                BootstrapResult.Failed(
                    message = errors.firstOrNull() ?: "Could not connect to household photos",
                    details = errors.toList(),
                )
        }
    }

    companion object {
        private const val TAG = "HouseholdNasBootstrap"
    }
}

sealed interface BootstrapResult {
    data class Success(
        val sources: List<String>,
        val warnings: List<String> = emptyList(),
    ) : BootstrapResult

    data class Failed(
        val message: String,
        val details: List<String> = emptyList(),
    ) : BootstrapResult
}

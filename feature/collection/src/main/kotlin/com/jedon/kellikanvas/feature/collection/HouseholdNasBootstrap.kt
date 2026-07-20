package com.jedon.kellikanvas.feature.collection

import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import kotlinx.coroutines.CancellationException

/**
 * Zero-friction first-launch connect: household SMB Frame TV 16×9 folder,
 * then the DLNA equivalent if SMB is unavailable.
 * Replaces prior household auto-roots (SMB/DLNA) while retaining SAF roots.
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
                val smbResult = smb.connectHousehold(replaceNetworkRoots = true)
                added += "SMB ${smbResult.share}/${smbResult.roots.joinToString()}"
                DiagLog.i(
                    TAG,
                    "Household SMB connected host=${smbResult.host} roots=${smbResult.roots}",
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "Household SMB bootstrap failed", failure)
                errors += failure.message?.take(120) ?: "SMB connect failed"
            }
        } else {
            DiagLog.i(TAG, "Household SMB credentials not baked in; skipping SMB bootstrap")
        }

        if (added.isEmpty()) {
            try {
                val server = dlna.tryKnownHosts()
                val folder =
                    PhotosFolderPicker.selectedFrameTv16x9Folder { objectId ->
                        dlna.listChildren(server.profile, folderObjectId = objectId)
                    }
                if (folder != null) {
                    dlna.saveSelection(
                        profile = server.profile,
                        friendlyName = server.friendlyName,
                        folders = listOf(folder),
                        replaceNetworkRoots = true,
                    )
                    added += "DLNA ${folder.label}"
                    DiagLog.i(
                        TAG,
                        "DLNA Frame TV 16X9 auto-selected via ${server.matchedHost ?: server.friendlyName}",
                    )
                } else {
                    errors += "DLNA connected but Frame TV 16X9 folder not found"
                    DiagLog.w(TAG, "DLNA Frame TV 16X9 folder not found under Photos")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "DLNA Frame TV bootstrap failed", failure)
                errors += failure.message?.take(120) ?: "DLNA connect failed"
            }
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

        private val STALE_OBJECT_IDS =
            setOf(
                "Digital Photos",
                "Cell Phone Photos",
                "Photos for frame TV and printing",
                "Frame TV landscape photos_mix",
                "Images",
                "kelli_resized",
                "Business Card photos 2026",
                "Colonial Williamsburg Grand Illumination",
                "Jedons cell phone photos incl Swept Away",
                "Photography files",
                "Canvas",
                "Media/Pictures",
                "Photos",
            )

        /**
         * True when bootstrap should run: empty collection, or prior household auto-roots
         * that are not the preferred Frame TV 16×9 folder.
         */
        fun needsHouseholdRootReplace(roots: List<SelectedRoot>): Boolean {
            if (roots.isEmpty()) return true
            if (roots.any(::isStaleHouseholdRoot)) return true
            return !roots.any(::isPreferredHouseholdRoot)
        }

        fun isPreferredHouseholdRoot(root: SelectedRoot): Boolean {
            if (HouseholdNasDefaults.isPreferredSmbRoot(root.objectId.value)) return true
            val label = root.displayLabel
            return label.equals("16X9", ignoreCase = true) ||
                label.equals(HouseholdNasDefaults.FRAME_TV_16X9_PATH, ignoreCase = true) ||
                label.equals("Frame TV landscape photos_mix/16X9", ignoreCase = true)
        }

        fun isStaleHouseholdRoot(root: SelectedRoot): Boolean {
            if (isPreferredHouseholdRoot(root)) return false
            val objectId = root.objectId.value
            if (STALE_OBJECT_IDS.any { it.equals(objectId, ignoreCase = true) }) return true
            val label = root.displayLabel
            return STALE_OBJECT_IDS.any { it.equals(label, ignoreCase = true) } ||
                label.equals("Photos", ignoreCase = true)
        }
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

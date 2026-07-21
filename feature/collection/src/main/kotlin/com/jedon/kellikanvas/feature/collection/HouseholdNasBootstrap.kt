package com.jedon.kellikanvas.feature.collection

import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.logging.BootstrapTrace
import com.jedon.kellikanvas.logging.BootstrapTraceRecord
import com.jedon.kellikanvas.logging.BootstrapTraceStep
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.diagnosticSummary
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
    private val recordKnownGoodIp: (String) -> Unit = {},
) {
    suspend fun ensurePhotosCollection(): BootstrapResult {
        val startedAtMillis = System.currentTimeMillis()
        val added = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val steps = mutableListOf<BootstrapTraceStep>()

        fun trace(step: BootstrapTraceStep) {
            steps += step
            val suffix = step.detail?.let { ": $it" }.orEmpty()
            if (step.ok) {
                DiagLog.i(TAG, "Bootstrap step ok — ${step.name}$suffix")
            } else {
                DiagLog.w(TAG, "Bootstrap step failed — ${step.name}$suffix")
            }
        }

        if (hasHouseholdSmbCredentials()) {
            trace(BootstrapTraceStep("SMB credentials", ok = true))
            try {
                val smbResult = smb.connectHousehold(replaceNetworkRoots = true, onStep = ::trace)
                recordKnownGoodIp(smbResult.host)
                added += "SMB ${smbResult.share}/${smbResult.roots.joinToString()}"
                DiagLog.i(
                    TAG,
                    "Household SMB connected host=${smbResult.host} roots=${smbResult.roots}",
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "Household SMB bootstrap failed", failure)
                trace(
                    BootstrapTraceStep(
                        name = "SMB connect",
                        ok = false,
                        detail = failure.diagnosticSummary(),
                    ),
                )
                errors += failure.message?.take(120) ?: "SMB connect failed"
            }
        } else {
            DiagLog.i(TAG, "Household SMB credentials not baked in; skipping SMB bootstrap")
            trace(
                BootstrapTraceStep(
                    name = "SMB credentials",
                    ok = false,
                    detail = "not baked into this build; skipping SMB",
                ),
            )
        }

        if (added.isEmpty()) {
            val server =
                try {
                    dlna.tryKnownHosts().also { discovered ->
                        trace(
                            BootstrapTraceStep(
                                name = "DLNA discovery",
                                ok = true,
                                detail = discovered.matchedHost ?: discovered.friendlyName,
                            ),
                        )
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    DiagLog.w(TAG, "DLNA discovery failed", failure)
                    trace(
                        BootstrapTraceStep(
                            name = "DLNA discovery",
                            ok = false,
                            detail = failure.diagnosticSummary(),
                        ),
                    )
                    errors += failure.message?.take(120) ?: "DLNA connect failed"
                    null
                }
            if (server != null) {
                try {
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
                        server.matchedHost?.let(recordKnownGoodIp)
                        added += "DLNA ${folder.label}"
                        trace(
                            BootstrapTraceStep(
                                name = "DLNA folder resolution",
                                ok = true,
                                detail = folder.label,
                            ),
                        )
                        DiagLog.i(
                            TAG,
                            "DLNA Frame TV 16X9 auto-selected via ${server.matchedHost ?: server.friendlyName}",
                        )
                    } else {
                        errors += "DLNA connected but Frame TV 16X9 folder not found"
                        trace(
                            BootstrapTraceStep(
                                name = "DLNA folder resolution",
                                ok = false,
                                detail = "Frame TV 16X9 folder not found under Photos",
                            ),
                        )
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    DiagLog.w(TAG, "DLNA Frame TV bootstrap failed", failure)
                    trace(
                        BootstrapTraceStep(
                            name = "DLNA folder resolution",
                            ok = false,
                            detail = failure.diagnosticSummary(),
                        ),
                    )
                    errors += failure.message?.take(120) ?: "DLNA connect failed"
                }
            }
        }

        val result =
            when {
                added.isNotEmpty() ->
                    BootstrapResult.Success(sources = added.toList(), warnings = errors.toList())
                else ->
                    BootstrapResult.Failed(
                        message = errors.firstOrNull() ?: "Could not connect to household photos",
                        details = errors.toList(),
                    )
            }
        BootstrapTrace.record(
            BootstrapTraceRecord(
                startedAtMillis = startedAtMillis,
                steps = steps.toList(),
                result =
                when (result) {
                    is BootstrapResult.Success -> "Success: ${result.sources.joinToString()}"
                    is BootstrapResult.Failed -> "Failed: ${result.message}"
                },
            ),
        )
        return result
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

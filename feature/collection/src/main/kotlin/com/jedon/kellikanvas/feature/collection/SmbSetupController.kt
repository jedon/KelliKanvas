package com.jedon.kellikanvas.feature.collection

import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SmbConnection
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.DEFAULT_PAGE_LIMIT
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import com.jedon.kellikanvas.source.smb.HouseholdSmbShare
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbPath
import com.jedon.kellikanvas.source.smb.SmbProfile
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter
import kotlinx.coroutines.CancellationException
import java.util.UUID

class SmbSetupController(
    private val database: KelliKanvasDatabase,
    private val credentialVault: CredentialVault,
    private val householdUsername: String,
    private val householdPassword: CharArray,
    private val adapterFactory: (SmbProfile, SmbCredentials) -> SourceAdapter,
    private val resolvePreferredHost: suspend () -> String? = { null },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val profileIdFactory: () -> SourceProfileId = {
        SourceProfileId("smb-${UUID.randomUUID()}")
    },
) {
    /**
     * Connects using baked-in household hosts/shares and build-time credentials.
     * Auto-selects probe-proven photo roots that exist on the share.
     *
     * @param replaceNetworkRoots when true, drops prior SMB/DLNA roots (keeps SAF).
     */
    suspend fun connectHousehold(replaceNetworkRoots: Boolean = false): HouseholdConnectResult {
        require(householdUsername.isNotBlank()) {
            "Household SMB username missing (set QNAP_NAS_USERNAME for the build)"
        }
        require(householdPassword.isNotEmpty()) {
            "Household SMB password missing (set QNAP_NAS_PASSWORD for the build)"
        }
        var lastFailure: Throwable? = null
        val credentials =
            SmbCredentials(
                username = householdUsername,
                password = householdPassword.copyOf(),
                domain = "",
            )
        try {
            for (host in householdHostCandidates()) {
                for (shareDef in HouseholdNasDefaults.PHOTO_SHARES) {
                    try {
                        val profile =
                            SmbProfile(
                                id = profileIdFactory(),
                                host = host,
                                port = HouseholdNasDefaults.PORT,
                                share = shareDef.share,
                                username = householdUsername,
                            )
                        val adapter = adapterFactory(profile, credentials)
                        adapter.probe()
                        val availableRoots = existingPhotoRoots(adapter, shareDef)
                        if (availableRoots.isEmpty()) continue
                        val collectionId =
                            saveSelection(
                                profile = profile,
                                credentials = credentials,
                                displayName = "${HouseholdNasDefaults.DISPLAY_NAME} (${shareDef.displayName})",
                                folders =
                                availableRoots.map { path ->
                                    SelectedFolder(
                                        objectId = path,
                                        label = SmbPath.displayName(path).ifBlank { shareDef.share },
                                        includeDescendants = true,
                                    )
                                },
                                replaceNetworkRoots = replaceNetworkRoots,
                            )
                        return HouseholdConnectResult(
                            collectionId = collectionId,
                            host = host,
                            share = shareDef.share,
                            rootCount = availableRoots.size,
                            roots = availableRoots,
                        )
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                }
            }
            throw IllegalStateException(
                "Could not reach household NAS photo shares",
                lastFailure,
            )
        } finally {
            credentials.clear()
        }
    }

    suspend fun listChildren(
        profile: SmbProfile,
        credentials: SmbCredentials,
        folderObjectId: String = SmbSourceAdapter.ROOT_OBJECT_ID,
    ): List<BrowseEntry> = adapterFactory(profile, credentials)
        .listChildren(
            folder = FolderRef(profile.id, ProviderObjectId(folderObjectId)),
            cursor = null,
            limit = DEFAULT_PAGE_LIMIT,
        ).items
        .mapNotNull { entry ->
            when (entry) {
                is SourceEntry.Folder ->
                    BrowseEntry(
                        objectId = entry.ref.objectId.value,
                        title = entry.name,
                        isFolder = true,
                    )
                is SourceEntry.Photo -> null
            }
        }

    suspend fun saveSelection(
        profile: SmbProfile,
        credentials: SmbCredentials,
        displayName: String,
        folders: List<SelectedFolder>,
        replaceNetworkRoots: Boolean = false,
    ): String {
        val collectionId = CatalogIds.DEFAULT_COLLECTION_ID
        database.sourceProfiles.upsert(
            SourceProfile(
                id = profile.id,
                kind = SourceKind.SMB,
                displayName = displayName,
                createdAtMillis = nowMillis(),
            ),
        )
        database.smbConnections.upsert(
            SmbConnection(
                profileId = profile.id,
                host = profile.host,
                port = profile.port,
                share = profile.share,
                domain = profile.domain,
                username = profile.username,
                displayName = displayName,
            ),
        )
        credentialVault.write(profile.id, credentials.password)

        val previousRoots = database.selectedRoots.list(collectionId)
        val collectionLabel =
            if (previousRoots.isEmpty()) {
                displayName
            } else {
                database.collections.get(collectionId)?.label ?: displayName
            }
        database.collections.upsert(CatalogCollection(collectionId, collectionLabel))

        val retainedRoots =
            previousRoots.filter { root ->
                if (root.profileId == profile.id) return@filter false
                if (!replaceNetworkRoots) return@filter true
                database.safConnections.get(root.profileId) != null
            }
        val selectedRoots =
            folders.map { folder ->
                SelectedRoot(
                    collectionId = collectionId,
                    profileId = profile.id,
                    objectId = ProviderObjectId(SmbPath.normalize(folder.objectId)),
                    displayLabel = folder.label,
                    includeDescendants = folder.includeDescendants,
                )
            }
        database.selectedRoots.replaceAllForCollection(
            collectionId = collectionId,
            roots = retainedRoots + selectedRoots,
        )
        return collectionId
    }

    /**
     * Resolver-preferred host first, then the full static list unchanged so a
     * resolver miss can never make bootstrap worse than the hardcoded behavior.
     */
    private suspend fun householdHostCandidates(): List<String> {
        val preferred =
            try {
                resolvePreferredHost()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "NAS host resolution failed; using static candidates", failure)
                null
            }
        return (listOfNotNull(preferred) + HouseholdNasDefaults.HOST_CANDIDATES).distinct()
    }

    private suspend fun existingPhotoRoots(
        adapter: SourceAdapter,
        shareDef: HouseholdSmbShare,
    ): List<String> {
        val found = mutableListOf<String>()
        for (root in shareDef.photoRoots) {
            val normalized = SmbPath.normalize(root)
            val parentRaw = normalized.substringBeforeLast('/', missingDelimiterValue = "")
            val parent =
                if (parentRaw.isEmpty()) SmbSourceAdapter.ROOT_OBJECT_ID else parentRaw
            val name = SmbPath.displayName(normalized)
            val children =
                runCatching {
                    adapter.listChildren(
                        folder = FolderRef(adapter.profileId, ProviderObjectId(parent)),
                        cursor = null,
                        limit = DEFAULT_PAGE_LIMIT,
                    ).items
                }.getOrElse { emptyList() }
            val exists =
                children.any { entry ->
                    entry is SourceEntry.Folder &&
                        (
                            entry.ref.objectId.value == normalized ||
                                entry.name.equals(name, ignoreCase = true)
                            )
                }
            if (exists) found += normalized
        }
        return found
    }

    private companion object {
        const val TAG = "SmbSetupController"
    }
}

data class HouseholdConnectResult(
    val collectionId: String,
    val host: String,
    val share: String,
    val rootCount: Int,
    val roots: List<String>,
)

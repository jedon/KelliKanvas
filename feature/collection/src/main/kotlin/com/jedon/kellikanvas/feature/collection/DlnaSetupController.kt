package com.jedon.kellikanvas.feature.collection

import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.DlnaConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.source.DEFAULT_PAGE_LIMIT
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.dlna.BuiltInResolveResult
import com.jedon.kellikanvas.source.dlna.DlnaProfile

class DlnaSetupController(
    private val database: KelliKanvasDatabase,
    private val discoverProfiles: suspend () -> List<Pair<String, DlnaProfile>>,
    private val resolveManual: suspend (String) -> DlnaProfile,
    private val resolveBuiltIn: suspend () -> BuiltInResolveResult,
    private val adapterFactory: (DlnaProfile) -> SourceAdapter,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun discover(): List<DiscoveredServer> = discoverProfiles().map { (friendlyName, profile) ->
        DiscoveredServer(friendlyName, profile)
    }

    suspend fun resolveHost(input: String): DiscoveredServer {
        val profile = resolveManual(input)
        val friendlyName = input.trim().ifBlank { profile.id.value }
        return DiscoveredServer(friendlyName, profile)
    }

    /** Tries baked-in household NAS hosts when SSDP finds nothing. */
    suspend fun tryKnownHosts(): DiscoveredServer {
        val result = resolveBuiltIn()
        return DiscoveredServer(
            friendlyName = result.matchedHost,
            profile = result.profile,
            matchedHost = result.matchedHost,
        )
    }

    suspend fun listChildren(
        profile: DlnaProfile,
        folderObjectId: String = "0",
    ): List<BrowseEntry> = adapterFactory(profile)
        .listChildren(
            folder = FolderRef(profile.id, profile.stableObjectId(folderObjectId)),
            cursor = null,
            limit = DEFAULT_PAGE_LIMIT,
        )
        .items
        .mapNotNull { entry ->
            when (entry) {
                is SourceEntry.Folder -> BrowseEntry(
                    objectId = entry.ref.objectId.value,
                    title = entry.name,
                    isFolder = true,
                )
                is SourceEntry.Photo -> null
            }
        }

    suspend fun saveSelection(
        profile: DlnaProfile,
        friendlyName: String,
        folders: List<SelectedFolder>,
    ): String {
        val descriptionLocation = requireNotNull(profile.descriptionLocation) {
            "DLNA profile requires description location"
        }
        val controlUrl = requireNotNull(profile.controlUrl) {
            "DLNA profile requires ContentDirectory control URL"
        }
        val collectionId = CatalogIds.DEFAULT_COLLECTION_ID
        database.sourceProfiles.upsert(
            SourceProfile(
                id = profile.id,
                kind = SourceKind.DLNA,
                displayName = friendlyName,
                createdAtMillis = nowMillis(),
            ),
        )
        database.dlnaConnections.upsert(
            DlnaConnection(
                profileId = profile.id,
                serverUdn = profile.serverUdn,
                descriptionLocation = descriptionLocation.toString(),
                controlUrl = controlUrl.toString(),
                contentDirectoryVersion = profile.contentDirectoryVersion,
                displayName = friendlyName,
            ),
        )

        val previousRoots = database.selectedRoots.list(collectionId)
        val collectionLabel =
            if (previousRoots.isEmpty()) {
                friendlyName
            } else {
                database.collections.get(collectionId)?.label ?: friendlyName
            }
        database.collections.upsert(CatalogCollection(collectionId, collectionLabel))

        val retainedRoots = previousRoots.filter { it.profileId != profile.id }
        val selectedRoots = folders.map { folder ->
            SelectedRoot(
                collectionId = collectionId,
                profileId = profile.id,
                objectId = ProviderObjectId(folder.objectId),
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
}

data class DiscoveredServer(
    val friendlyName: String,
    val profile: DlnaProfile,
    val matchedHost: String? = null,
)

data class BrowseEntry(
    val objectId: String,
    val title: String,
    val isFolder: Boolean,
)

data class SelectedFolder(
    val objectId: String,
    val label: String,
    val includeDescendants: Boolean,
)

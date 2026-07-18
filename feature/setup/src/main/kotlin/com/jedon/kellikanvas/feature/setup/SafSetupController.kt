package com.jedon.kellikanvas.feature.setup

import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SafConnection
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.source.saf.SafProfile

class SafSetupController(
    private val database: KelliKanvasDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun complete(
        profile: SafProfile,
        displayName: String,
        includeDescendants: Boolean,
    ): String {
        val collectionId = DEFAULT_COLLECTION_ID
        val createdAtMillis = nowMillis()
        database.sourceProfiles.upsert(
            SourceProfile(
                id = profile.id,
                kind = SourceKind.SAF,
                displayName = displayName,
                createdAtMillis = createdAtMillis,
            ),
        )
        database.safConnections.upsert(
            SafConnection(
                profileId = profile.id,
                treeUri = profile.grant.treeUri.toString(),
            ),
        )
        database.collections.upsert(CatalogCollection(collectionId, displayName))
        val previousRoots = database.selectedRoots.list(collectionId)
        database.selectedRoots.replaceAllForCollection(
            collectionId = collectionId,
            roots = listOf(
                SelectedRoot(
                    collectionId = collectionId,
                    profileId = profile.id,
                    objectId = ProviderObjectId(profile.grant.documentId),
                    displayLabel = displayName,
                    includeDescendants = includeDescendants,
                ),
            ),
        )
        for (staleProfileId in previousRoots.map { it.profileId }.distinct()) {
            if (staleProfileId != profile.id) {
                database.sourceProfiles.delete(staleProfileId)
            }
        }
        return collectionId
    }

    companion object {
        const val DEFAULT_COLLECTION_ID = "default"
    }
}

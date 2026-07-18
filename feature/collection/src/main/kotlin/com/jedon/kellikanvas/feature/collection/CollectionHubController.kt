package com.jedon.kellikanvas.feature.collection

import androidx.room.withTransaction
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot

class CollectionHubController(
    private val database: KelliKanvasDatabase,
) {
    suspend fun listRoots(): List<SelectedRoot> = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)

    suspend fun removeRoot(root: SelectedRoot) {
        database.withTransaction {
            database.selectedRoots.delete(
                collectionId = root.collectionId,
                profileId = root.profileId,
                objectId = root.objectId,
            )
            val hasRemainingRoots =
                database.collections.list().any { collection ->
                    database.selectedRoots.list(collection.id).any { it.profileId == root.profileId }
                }
            if (!hasRemainingRoots) {
                database.sourceProfiles.delete(root.profileId)
            }
        }
    }
}

package com.jedon.kellikanvas.feature.collection

import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.setup.SafSetupController

class CollectionHubController(
    private val database: KelliKanvasDatabase,
) {
    suspend fun listRoots(): List<SelectedRoot> =
        database.selectedRoots.list(SafSetupController.DEFAULT_COLLECTION_ID)

    suspend fun removeRoot(root: SelectedRoot) {
        database.selectedRoots.delete(
            collectionId = root.collectionId,
            profileId = root.profileId,
            objectId = root.objectId,
        )
        val hasRemainingRoots =
            database.selectedRoots.list(root.collectionId).any { it.profileId == root.profileId }
        if (!hasRemainingRoots) {
            database.sourceProfiles.delete(root.profileId)
        }
    }
}

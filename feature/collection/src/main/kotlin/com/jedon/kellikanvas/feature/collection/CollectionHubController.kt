package com.jedon.kellikanvas.feature.collection

import androidx.room.withTransaction
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.SourceProfileId

class RetiredSourceProfile(
    val profileId: SourceProfileId,
    val safTreeUri: String?,
    val googleConnection: GoogleConnection? = null,
)

class CollectionHubController(
    private val database: KelliKanvasDatabase,
    private val onProfileRetired: suspend (RetiredSourceProfile) -> Unit = {},
) {
    suspend fun listRoots(): List<SelectedRoot> = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)

    suspend fun removeRoot(root: SelectedRoot) {
        val retired =
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
                    val treeUri = database.safConnections.get(root.profileId)?.treeUri
                    val google = database.googleConnections.get(root.profileId)
                    database.sourceProfiles.delete(root.profileId)
                    RetiredSourceProfile(root.profileId, treeUri, google)
                } else {
                    null
                }
            }
        if (retired != null) {
            onProfileRetired(retired)
        }
    }
}

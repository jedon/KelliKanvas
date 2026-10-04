package com.jedon.kellikanvas.google

import androidx.room.withTransaction
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.catalog.SourceProfileStatus
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId

class GoogleSourceController(private val database: KelliKanvasDatabase) {
    suspend fun saveConnection(connection: GoogleConnection, kind: SourceKind, ready: Boolean = false) {
        require(kind == SourceKind.GOOGLE_DRIVE || kind == SourceKind.GOOGLE_PHOTOS)
        database.withTransaction {
            val existing = database.sourceProfiles.get(connection.profileId)
            require(existing == null || existing.kind == SourceProfileKind.Known(kind))
            database.sourceProfiles.upsert(
                SourceProfile(
                    connection.profileId,
                    kind,
                    if (kind == SourceKind.GOOGLE_DRIVE) "Google Drive" else "Google Photos",
                    if (ready) SourceProfileStatus.AVAILABLE else SourceProfileStatus.UNKNOWN,
                    createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                ),
            )
            database.googleConnections.upsert(connection)
        }
    }

    suspend fun select(connection: GoogleConnection, kind: SourceKind, folder: FolderRef, label: String, descendants: Boolean) {
        require(folder.profileId == connection.profileId)
        database.withTransaction {
            saveConnection(connection, kind, ready = true)
            if (database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID) == null) {
                database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Your photos"))
            }
            database.selectedRoots.replace(SelectedRoot(CatalogIds.DEFAULT_COLLECTION_ID, folder.profileId, folder.objectId, label, descendants))
        }
    }

    suspend fun removeUnused(id: SourceProfileId): Boolean = database.withTransaction {
        if (database.collections.list().any { collection -> database.selectedRoots.list(collection.id).any { it.profileId == id } }) {
            false
        } else {
            database.sourceProfiles.delete(id)
            true
        }
    }
}

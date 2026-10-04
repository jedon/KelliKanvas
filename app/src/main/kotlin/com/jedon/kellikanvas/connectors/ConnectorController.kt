package com.jedon.kellikanvas.connectors

import androidx.room.withTransaction
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileStatus
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.ConnectorStore

class ConnectorController(private val database: KelliKanvasDatabase, private val store: ConnectorStore) {
    suspend fun select(id: SourceProfileId, config: ConnectorConfiguration, folder: SourceEntry.Folder, descendants: Boolean) {
        require(folder.ref.profileId == id)
        val profile = database.sourceProfiles.get(id)
        require(profile == null || profile.kind == com.jedon.kellikanvas.catalog.SourceProfileKind.Known(config.provider.kind))
        val previous = store.read(id)
        require(previous == null || (previous.provider == config.provider && previous.endpoint == config.endpoint && previous.username == config.username)) { "Reconnect the original account and server, or add a new source" }
        store.write(id, config)
        try {
            database.withTransaction {
                val existing = database.sourceProfiles.get(id)
                database.sourceProfiles.upsert(SourceProfile(id, config.provider.kind, config.provider.title, SourceProfileStatus.AVAILABLE, createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis()))
                if (database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID) == null) database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Your photos"))
                database.selectedRoots.replace(SelectedRoot(CatalogIds.DEFAULT_COLLECTION_ID, id, folder.ref.objectId, folder.name, descendants))
            }
        } catch (failure: Exception) {
            if (previous == null) store.remove(id) else store.write(id, previous)
            throw failure
        }
    }
}

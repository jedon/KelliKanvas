package com.jedon.kellikanvas.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface RoomSourceProfileDao {
    @Upsert
    suspend fun upsert(entity: SourceProfileEntity)

    @Query("SELECT * FROM source_profiles WHERE profile_id = :profileId")
    suspend fun get(profileId: String): SourceProfileEntity?

    @Query("DELETE FROM source_profiles WHERE profile_id = :profileId")
    suspend fun delete(profileId: String)
}

@Dao
internal interface RoomCollectionDao {
    @Upsert
    suspend fun upsert(entity: CatalogCollectionEntity)

    @Query("SELECT * FROM collections WHERE collection_id = :collectionId")
    suspend fun get(collectionId: String): CatalogCollectionEntity?

    @Query("SELECT * FROM collections")
    suspend fun list(): List<CatalogCollectionEntity>
}

@Dao
internal interface RoomSelectedRootDao {
    @Upsert
    suspend fun upsert(entity: SelectedRootEntity)

    @Query(
        "DELETE FROM selected_root_filters WHERE collection_id = :collectionId " +
            "AND profile_id = :profileId AND object_id = :objectId",
    )
    suspend fun deleteFilters(
        collectionId: String,
        profileId: String,
        objectId: String,
    )

    @Query("DELETE FROM selected_root_filters WHERE collection_id = :collectionId")
    suspend fun deleteAllFilters(collectionId: String)

    @Query("DELETE FROM selected_roots WHERE collection_id = :collectionId")
    suspend fun deleteAll(collectionId: String)

    @Query(
        "DELETE FROM selected_roots WHERE collection_id = :collectionId " +
            "AND profile_id = :profileId AND object_id = :objectId",
    )
    suspend fun deleteRoot(
        collectionId: String,
        profileId: String,
        objectId: String,
    )

    @Insert
    suspend fun insertFilters(entities: List<SelectedRootFilterEntity>)

    @Query(
        "SELECT * FROM selected_roots WHERE collection_id = :collectionId " +
            "ORDER BY profile_id, object_id",
    )
    suspend fun list(collectionId: String): List<SelectedRootEntity>

    @Query(
        "SELECT * FROM selected_root_filters WHERE collection_id = :collectionId " +
            "ORDER BY profile_id, object_id, filter_value",
    )
    suspend fun listFilters(collectionId: String): List<SelectedRootFilterEntity>

    @Transaction
    suspend fun listAggregates(collectionId: String): List<SelectedRootAggregate> {
        val filters =
            listFilters(collectionId).groupBy {
                SelectedRootKey(it.collectionId, it.profileId, it.objectId)
            }
        return list(collectionId).map { root ->
            SelectedRootAggregate(
                root = root,
                filters =
                filters[
                    SelectedRootKey(
                        root.collectionId,
                        root.profileId,
                        root.objectId,
                    ),
                ].orEmpty(),
            )
        }
    }
}

internal data class SelectedRootKey(
    val collectionId: String,
    val profileId: String,
    val objectId: String,
)

internal data class SelectedRootAggregate(
    val root: SelectedRootEntity,
    val filters: List<SelectedRootFilterEntity>,
)

@Dao
internal interface RoomCatalogAssetDao {
    @Upsert
    suspend fun upsert(entity: CatalogAssetEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CatalogAssetEntity>)

    @Query(
        "SELECT * FROM catalog_assets WHERE profile_id = :profileId AND object_id = :objectId",
    )
    suspend fun get(
        profileId: String,
        objectId: String,
    ): CatalogAssetEntity?

    @Query(
        "DELETE FROM catalog_assets WHERE profile_id = :profileId AND object_id = :objectId",
    )
    suspend fun delete(
        profileId: String,
        objectId: String,
    )
}

@Dao
internal interface RoomPlaylistCycleDao {
    @Insert
    suspend fun insert(entity: PlaylistCycleEntity)

    @Query("SELECT * FROM playlist_cycles WHERE cycle_id = :cycleId")
    suspend fun get(cycleId: String): PlaylistCycleEntity?

    @Query("DELETE FROM playlist_cycles WHERE cycle_id = :cycleId")
    suspend fun delete(cycleId: String)
}

@Dao
internal interface RoomPlaylistCycleItemDao {
    @Insert
    suspend fun insert(entity: PlaylistCycleItemEntity)

    @Insert
    suspend fun insertAll(entities: List<PlaylistCycleItemEntity>)

    @Query("SELECT * FROM playlist_cycle_items WHERE cycle_id = :cycleId ORDER BY ordinal")
    suspend fun list(cycleId: String): List<PlaylistCycleItemEntity>
}

@Dao
internal interface RoomConsumedPortraitPartnerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConsumedPortraitPartnerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ConsumedPortraitPartnerEntity>)

    @Query(
        "SELECT * FROM consumed_portrait_partners WHERE cycle_id = :cycleId " +
            "ORDER BY profile_id, object_id",
    )
    suspend fun list(cycleId: String): List<ConsumedPortraitPartnerEntity>
}

@Dao
internal interface RoomSlideshowSessionDao {
    @Upsert
    suspend fun upsert(entity: SlideshowSessionEntity)

    @Query(
        "DELETE FROM slideshow_session_last_presented WHERE collection_id = :collectionId",
    )
    suspend fun deleteLastPresented(collectionId: String)

    @Insert
    suspend fun insertLastPresented(entity: SlideshowSessionLastPresentedEntity)

    @Query("SELECT * FROM slideshow_sessions WHERE collection_id = :collectionId")
    suspend fun get(collectionId: String): SlideshowSessionEntity?

    @Query(
        "SELECT * FROM slideshow_session_last_presented WHERE collection_id = :collectionId",
    )
    suspend fun getLastPresented(collectionId: String): SlideshowSessionLastPresentedEntity?

    @Transaction
    suspend fun persist(
        entity: SlideshowSessionEntity,
        lastPresented: SlideshowSessionLastPresentedEntity?,
    ) {
        upsert(entity)
        deleteLastPresented(entity.collectionId)
        lastPresented?.let { insertLastPresented(it) }
    }

    @Transaction
    suspend fun getAggregate(collectionId: String): SlideshowSessionAggregate? {
        val session = get(collectionId) ?: return null
        return SlideshowSessionAggregate(session, getLastPresented(collectionId))
    }

    @Query("DELETE FROM slideshow_sessions WHERE collection_id = :collectionId")
    suspend fun delete(collectionId: String)
}

internal data class SlideshowSessionAggregate(
    val session: SlideshowSessionEntity,
    val lastPresented: SlideshowSessionLastPresentedEntity?,
)

@Dao
internal interface RoomSafConnectionDao {
    @Upsert
    suspend fun upsert(entity: SafConnectionEntity)

    @Query("SELECT * FROM saf_connections WHERE profile_id = :profileId")
    suspend fun get(profileId: String): SafConnectionEntity?

    @Query("DELETE FROM saf_connections WHERE profile_id = :profileId")
    suspend fun delete(profileId: String)
}

@Dao
internal interface RoomDlnaConnectionDao {
    @Upsert
    suspend fun upsert(entity: DlnaConnectionEntity)

    @Query("SELECT * FROM dlna_connections WHERE profile_id = :profileId")
    suspend fun get(profileId: String): DlnaConnectionEntity?

    @Query("DELETE FROM dlna_connections WHERE profile_id = :profileId")
    suspend fun delete(profileId: String)
}

@Dao
internal interface RoomSmbConnectionDao {
    @Upsert
    suspend fun upsert(entity: SmbConnectionEntity)

    @Query("SELECT * FROM smb_connections WHERE profile_id = :profileId")
    suspend fun get(profileId: String): SmbConnectionEntity?

    @Query("DELETE FROM smb_connections WHERE profile_id = :profileId")
    suspend fun delete(profileId: String)
}


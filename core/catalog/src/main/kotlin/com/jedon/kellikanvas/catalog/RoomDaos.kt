package com.jedon.kellikanvas.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
internal interface RoomSelectedRootDao {
    @Upsert
    suspend fun upsert(entity: SelectedRootEntity)

    @Query("SELECT * FROM selected_roots WHERE profile_id = :profileId ORDER BY object_id")
    suspend fun list(profileId: String): List<SelectedRootEntity>
}

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
    @Upsert
    suspend fun upsert(entity: PlaylistCycleEntity)

    @Query("SELECT * FROM playlist_cycles WHERE cycle_id = :cycleId")
    suspend fun get(cycleId: String): PlaylistCycleEntity?

    @Query("DELETE FROM playlist_cycles WHERE cycle_id = :cycleId")
    suspend fun delete(cycleId: String)
}

@Dao
internal interface RoomPlaylistCycleItemDao {
    @Insert
    suspend fun insert(entity: PlaylistCycleItemEntity)

    @Query("SELECT * FROM playlist_cycle_items WHERE cycle_id = :cycleId ORDER BY ordinal")
    suspend fun list(cycleId: String): List<PlaylistCycleItemEntity>
}

@Dao
internal interface RoomConsumedPortraitPartnerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConsumedPortraitPartnerEntity)

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

    @Query("SELECT * FROM slideshow_sessions WHERE collection_id = :collectionId")
    suspend fun get(collectionId: String): SlideshowSessionEntity?

    @Query("DELETE FROM slideshow_sessions WHERE collection_id = :collectionId")
    suspend fun delete(collectionId: String)
}

package com.jedon.kellikanvas.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceProfileEntity::class,
        SelectedRootEntity::class,
        CatalogAssetEntity::class,
        PlaylistCycleEntity::class,
        PlaylistCycleItemEntity::class,
        ConsumedPortraitPartnerEntity::class,
        SlideshowSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KelliKanvasDatabase : RoomDatabase() {
    internal abstract fun roomSourceProfiles(): RoomSourceProfileDao

    internal abstract fun roomSelectedRoots(): RoomSelectedRootDao

    internal abstract fun roomCatalogAssets(): RoomCatalogAssetDao

    internal abstract fun roomPlaylistCycles(): RoomPlaylistCycleDao

    internal abstract fun roomPlaylistCycleItems(): RoomPlaylistCycleItemDao

    internal abstract fun roomConsumedPortraitPartners(): RoomConsumedPortraitPartnerDao

    internal abstract fun roomSlideshowSessions(): RoomSlideshowSessionDao

    val sourceProfiles: SourceProfileDao by lazy {
        SourceProfileDao(roomSourceProfiles())
    }
    val selectedRoots: SelectedRootDao by lazy {
        SelectedRootDao(roomSelectedRoots())
    }
    val catalogAssets: CatalogAssetDao by lazy {
        CatalogAssetDao(roomCatalogAssets())
    }
    val playlistCycles: PlaylistCycleDao by lazy {
        PlaylistCycleDao(roomPlaylistCycles())
    }
    val playlistCycleItems: PlaylistCycleItemDao by lazy {
        PlaylistCycleItemDao(roomPlaylistCycleItems())
    }
    val consumedPortraitPartners: ConsumedPortraitPartnerDao by lazy {
        ConsumedPortraitPartnerDao(roomConsumedPortraitPartners())
    }
    val slideshowSessions: SlideshowSessionDao by lazy {
        SlideshowSessionDao(roomSlideshowSessions())
    }
}

object KelliKanvasDatabaseFactory {
    const val DATABASE_NAME: String = "kellikanvas-catalog.db"

    fun create(context: Context): KelliKanvasDatabase = Room.databaseBuilder(
        context.applicationContext,
        KelliKanvasDatabase::class.java,
        DATABASE_NAME,
    ).build()

    fun inMemory(context: Context): KelliKanvasDatabase = Room.inMemoryDatabaseBuilder(
        context.applicationContext,
        KelliKanvasDatabase::class.java,
    ).build()
}

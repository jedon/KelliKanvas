package com.jedon.kellikanvas.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceProfileEntity::class,
        CatalogCollectionEntity::class,
        SelectedRootEntity::class,
        SelectedRootFilterEntity::class,
        CatalogAssetEntity::class,
        PlaylistCycleEntity::class,
        PlaylistCycleItemEntity::class,
        ConsumedPortraitPartnerEntity::class,
        SlideshowSessionEntity::class,
        SlideshowSessionLastPresentedEntity::class,
        SafConnectionEntity::class,
        DlnaConnectionEntity::class,
        SmbConnectionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class KelliKanvasDatabase : RoomDatabase() {
    internal abstract fun roomSourceProfiles(): RoomSourceProfileDao

    internal abstract fun roomCollections(): RoomCollectionDao

    internal abstract fun roomSelectedRoots(): RoomSelectedRootDao

    internal abstract fun roomCatalogAssets(): RoomCatalogAssetDao

    internal abstract fun roomPlaylistCycles(): RoomPlaylistCycleDao

    internal abstract fun roomPlaylistCycleItems(): RoomPlaylistCycleItemDao

    internal abstract fun roomConsumedPortraitPartners(): RoomConsumedPortraitPartnerDao

    internal abstract fun roomSlideshowSessions(): RoomSlideshowSessionDao

    internal abstract fun roomSafConnections(): RoomSafConnectionDao

    internal abstract fun roomDlnaConnections(): RoomDlnaConnectionDao

    internal abstract fun roomSmbConnections(): RoomSmbConnectionDao

    val sourceProfiles: SourceProfileDao by lazy {
        SourceProfileDao(roomSourceProfiles())
    }
    val collections: CollectionDao by lazy {
        CollectionDao(roomCollections())
    }
    val selectedRoots: SelectedRootDao by lazy {
        SelectedRootDao(this, roomSelectedRoots())
    }
    val catalogAssets: CatalogAssetDao by lazy {
        CatalogAssetDao(roomCatalogAssets())
    }
    /**
     * Piecemeal cycle mutation is module-internal only.
     * Outside `core:catalog`, create or replace cycles via [cycleSnapshots].
     */
    internal val playlistCycles: PlaylistCycleDao by lazy {
        PlaylistCycleDao(roomPlaylistCycles())
    }
    internal val playlistCycleItems: PlaylistCycleItemDao by lazy {
        PlaylistCycleItemDao(roomPlaylistCycleItems())
    }
    internal val consumedPortraitPartners: ConsumedPortraitPartnerDao by lazy {
        ConsumedPortraitPartnerDao(roomConsumedPortraitPartners())
    }
    val slideshowSessions: SlideshowSessionDao by lazy {
        SlideshowSessionDao(roomSlideshowSessions())
    }
    val safConnections: SafConnectionDao by lazy {
        SafConnectionDao(roomSafConnections())
    }
    val dlnaConnections: DlnaConnectionDao by lazy {
        DlnaConnectionDao(roomDlnaConnections())
    }
    val smbConnections: SmbConnectionDao by lazy {
        SmbConnectionDao(roomSmbConnections())
    }
    val cycleSnapshots: CycleSnapshotDao by lazy {
        CycleSnapshotDao(this)
    }
}

object KelliKanvasDatabaseFactory {
    const val DATABASE_NAME: String = "kellikanvas-catalog.db"

    fun create(
        context: Context,
        databaseName: String = DATABASE_NAME,
    ): KelliKanvasDatabase = Room.databaseBuilder(
        context.applicationContext,
        KelliKanvasDatabase::class.java,
        databaseName,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()

    fun inMemory(context: Context): KelliKanvasDatabase = Room.inMemoryDatabaseBuilder(
        context.applicationContext,
        KelliKanvasDatabase::class.java,
    ).build()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saf_connections` (
                `profile_id` TEXT NOT NULL,
                `tree_uri` TEXT NOT NULL,
                PRIMARY KEY(`profile_id`),
                FOREIGN KEY(`profile_id`) REFERENCES `source_profiles`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dlna_connections` (
                `profile_id` TEXT NOT NULL,
                `server_udn` TEXT NOT NULL,
                `description_location` TEXT NOT NULL,
                `control_url` TEXT NOT NULL,
                `content_directory_version` INTEGER NOT NULL,
                `display_name` TEXT NOT NULL,
                PRIMARY KEY(`profile_id`),
                FOREIGN KEY(`profile_id`) REFERENCES `source_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `smb_connections` (
                `profile_id` TEXT NOT NULL,
                `host` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `share` TEXT NOT NULL,
                `domain` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                PRIMARY KEY(`profile_id`),
                FOREIGN KEY(`profile_id`) REFERENCES `source_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}


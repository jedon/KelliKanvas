package com.jedon.kellikanvas.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "source_profiles", primaryKeys = ["profile_id"])
internal data class SourceProfileEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

@Entity(
    tableName = "selected_roots",
    primaryKeys = ["profile_id", "object_id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SelectedRootEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
    @ColumnInfo(name = "recursive") val recursive: Boolean,
)

@Entity(tableName = "catalog_assets", primaryKeys = ["profile_id", "object_id"])
internal data class CatalogAssetEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "width") val width: Int?,
    @ColumnInfo(name = "height") val height: Int?,
    @ColumnInfo(name = "capture_time_millis") val captureTimeMillis: Long?,
    @ColumnInfo(name = "modified_at_millis") val modifiedAtMillis: Long?,
    @ColumnInfo(name = "version_tag") val versionTag: String?,
)

@Entity(
    tableName = "playlist_cycles",
    primaryKeys = ["cycle_id"],
    indices = [Index(value = ["collection_id"])],
)
internal data class PlaylistCycleEntity(
    @ColumnInfo(name = "cycle_id") val cycleId: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

@Entity(
    tableName = "playlist_cycle_items",
    primaryKeys = ["cycle_id", "ordinal"],
    indices = [
        Index(value = ["cycle_id", "profile_id", "object_id"], unique = true),
        Index(value = ["profile_id", "object_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCycleEntity::class,
            parentColumns = ["cycle_id"],
            childColumns = ["cycle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogAssetEntity::class,
            parentColumns = ["profile_id", "object_id"],
            childColumns = ["profile_id", "object_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class PlaylistCycleItemEntity(
    @ColumnInfo(name = "cycle_id") val cycleId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
)

@Entity(
    tableName = "consumed_portrait_partners",
    primaryKeys = ["cycle_id", "profile_id", "object_id"],
    indices = [Index(value = ["profile_id", "object_id"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCycleEntity::class,
            parentColumns = ["cycle_id"],
            childColumns = ["cycle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogAssetEntity::class,
            parentColumns = ["profile_id", "object_id"],
            childColumns = ["profile_id", "object_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class ConsumedPortraitPartnerEntity(
    @ColumnInfo(name = "cycle_id") val cycleId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
)

@Entity(
    tableName = "slideshow_sessions",
    primaryKeys = ["collection_id"],
    indices = [Index(value = ["cycle_id"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCycleEntity::class,
            parentColumns = ["cycle_id"],
            childColumns = ["cycle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class SlideshowSessionEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "cycle_id") val cycleId: String,
    @ColumnInfo(name = "current_ordinal") val currentOrdinal: Int,
    @ColumnInfo(name = "current_profile_id") val currentProfileId: String,
    @ColumnInfo(name = "current_object_id") val currentObjectId: String,
    @ColumnInfo(name = "last_profile_id") val lastProfileId: String?,
    @ColumnInfo(name = "last_object_id") val lastObjectId: String?,
)

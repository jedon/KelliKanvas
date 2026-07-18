package com.jedon.kellikanvas.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "source_profiles", primaryKeys = ["profile_id"])
internal data class SourceProfileEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "source_kind_code") val sourceKindCode: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "status_code") val statusCode: String,
    @ColumnInfo(name = "last_successful_refresh_millis") val lastSuccessfulRefreshMillis: Long?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

@Entity(tableName = "collections", primaryKeys = ["collection_id"])
internal data class CatalogCollectionEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "index_status_code") val indexStatusCode: String,
    @ColumnInfo(name = "last_indexed_at_millis") val lastIndexedAtMillis: Long?,
)

@Entity(
    tableName = "selected_roots",
    primaryKeys = ["collection_id", "profile_id", "object_id"],
    indices = [Index(value = ["profile_id"])],
    foreignKeys = [
        ForeignKey(
            entity = CatalogCollectionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SelectedRootEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
    @ColumnInfo(name = "display_label") val displayLabel: String,
    @ColumnInfo(name = "include_descendants") val includeDescendants: Boolean,
)

@Entity(
    tableName = "selected_root_filters",
    primaryKeys = ["collection_id", "profile_id", "object_id", "filter_value"],
    foreignKeys = [
        ForeignKey(
            entity = SelectedRootEntity::class,
            parentColumns = ["collection_id", "profile_id", "object_id"],
            childColumns = ["collection_id", "profile_id", "object_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SelectedRootFilterEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
    @ColumnInfo(name = "filter_value") val filterValue: String,
)

@Entity(
    tableName = "catalog_assets",
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
    indices = [Index(value = ["collection_id", "cycle_id"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = CatalogCollectionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class PlaylistCycleEntity(
    @ColumnInfo(name = "cycle_id") val cycleId: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "shuffle_seed") val shuffleSeed: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

@Entity(
    tableName = "playlist_cycle_items",
    primaryKeys = ["cycle_id", "ordinal"],
    indices = [
        Index(value = ["cycle_id", "profile_id", "object_id"], unique = true),
        Index(
            value = ["cycle_id", "ordinal", "profile_id", "object_id"],
            unique = true,
        ),
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
    indices = [Index(value = ["cycle_id", "profile_id", "object_id"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCycleItemEntity::class,
            parentColumns = ["cycle_id", "profile_id", "object_id"],
            childColumns = ["cycle_id", "profile_id", "object_id"],
            onDelete = ForeignKey.CASCADE,
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
    indices = [
        Index(value = ["collection_id", "cycle_id"]),
        Index(
            value = [
                "cycle_id",
                "current_ordinal",
                "current_profile_id",
                "current_object_id",
            ],
        ),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCycleEntity::class,
            parentColumns = ["collection_id", "cycle_id"],
            childColumns = ["collection_id", "cycle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PlaylistCycleItemEntity::class,
            parentColumns = [
                "cycle_id",
                "ordinal",
                "profile_id",
                "object_id",
            ],
            childColumns = [
                "cycle_id",
                "current_ordinal",
                "current_profile_id",
                "current_object_id",
            ],
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
)

@Entity(
    tableName = "slideshow_session_last_presented",
    primaryKeys = ["collection_id"],
    indices = [Index(value = ["profile_id", "object_id"])],
    foreignKeys = [
        ForeignKey(
            entity = SlideshowSessionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
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
internal data class SlideshowSessionLastPresentedEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "object_id") val objectId: String,
)

@Entity(
    tableName = "saf_connections",
    primaryKeys = ["profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SafConnectionEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
)


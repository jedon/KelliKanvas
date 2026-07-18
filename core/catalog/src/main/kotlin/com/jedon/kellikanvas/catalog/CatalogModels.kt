package com.jedon.kellikanvas.catalog

import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import java.util.Collections

sealed interface SourceProfileKind {
    data class Known(
        val value: SourceKind,
    ) : SourceProfileKind

    data class Unknown(
        val stableCode: String,
    ) : SourceProfileKind {
        init {
            require(stableCode.isNotBlank()) { "Unknown source kind code must not be blank" }
        }
    }
}

enum class SourceProfileStatus {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    REQUIRES_REPAIR,
}

data class SourceProfile(
    val id: SourceProfileId,
    val kind: SourceProfileKind,
    val displayName: String,
    val status: SourceProfileStatus = SourceProfileStatus.UNKNOWN,
    val lastSuccessfulRefreshMillis: Long? = null,
    val createdAtMillis: Long,
) {
    constructor(
        id: SourceProfileId,
        kind: SourceKind,
        displayName: String,
        status: SourceProfileStatus = SourceProfileStatus.UNKNOWN,
        lastSuccessfulRefreshMillis: Long? = null,
        createdAtMillis: Long,
    ) : this(
        id = id,
        kind = SourceProfileKind.Known(kind),
        displayName = displayName,
        status = status,
        lastSuccessfulRefreshMillis = lastSuccessfulRefreshMillis,
        createdAtMillis = createdAtMillis,
    )

    init {
        require(displayName.isNotBlank()) { "Source display name must not be blank" }
        require(lastSuccessfulRefreshMillis == null || lastSuccessfulRefreshMillis >= 0) {
            "Last successful refresh time must be nonnegative"
        }
        require(createdAtMillis >= 0) { "Source creation time must be nonnegative" }
    }
}

enum class CollectionIndexStatus {
    NOT_INDEXED,
    INDEXING,
    READY,
    FAILED,
    UNKNOWN,
}

data class CatalogCollection(
    val id: String,
    val label: String,
    val indexStatus: CollectionIndexStatus = CollectionIndexStatus.NOT_INDEXED,
    val lastIndexedAtMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Collection ID must not be blank" }
        require(label.isNotBlank()) { "Collection label must not be blank" }
        require(lastIndexedAtMillis == null || lastIndexedAtMillis >= 0) {
            "Last indexed time must be nonnegative"
        }
    }
}

class SelectedRoot(
    val collectionId: String,
    val profileId: SourceProfileId,
    val objectId: ProviderObjectId,
    val displayLabel: String,
    val includeDescendants: Boolean,
    fileTypeFilters: Set<String> = emptySet(),
) {
    val fileTypeFilters: Set<String> =
        Collections.unmodifiableSet(fileTypeFilters.toSortedSet())

    init {
        require(collectionId.isNotBlank()) { "Collection ID must not be blank" }
        require(displayLabel.isNotBlank()) { "Selected-root label must not be blank" }
        require(fileTypeFilters.none(String::isBlank)) {
            "Selected-root file-type filters must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean = other is SelectedRoot &&
        collectionId == other.collectionId &&
        profileId == other.profileId &&
        objectId == other.objectId &&
        displayLabel == other.displayLabel &&
        includeDescendants == other.includeDescendants &&
        fileTypeFilters == other.fileTypeFilters

    override fun hashCode(): Int = listOf(
        collectionId,
        profileId,
        objectId,
        displayLabel,
        includeDescendants,
        fileTypeFilters,
    ).hashCode()

    override fun toString(): String = "SelectedRoot(collectionId=$collectionId, profileId=$profileId, objectId=$objectId, " +
        "displayLabel=<redacted>, includeDescendants=$includeDescendants, " +
        "fileTypeFilters=$fileTypeFilters)"
}

data class CatalogAsset(
    val key: AssetKey,
    val mimeType: String,
    val displayName: String,
    val width: Int?,
    val height: Int?,
    val captureTimeMillis: Long?,
    val modifiedAtMillis: Long?,
    val versionToken: String?,
) {
    init {
        require(mimeType.isNotBlank()) { "Asset MIME type must not be blank" }
        require(displayName.isNotBlank()) { "Asset display name must not be blank" }
        require(width == null || width > 0) { "Asset width must be positive when known" }
        require(height == null || height > 0) { "Asset height must be positive when known" }
        require(captureTimeMillis == null || captureTimeMillis >= 0) {
            "Asset capture time must be nonnegative"
        }
        require(modifiedAtMillis == null || modifiedAtMillis >= 0) {
            "Asset modified time must be nonnegative"
        }
        require(versionToken == null || versionToken.isNotBlank()) {
            "Asset version token must not be blank"
        }
    }
}

data class PlaylistCycle(
    val id: String,
    val collectionId: String,
    val shuffleSeed: String,
    val createdAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Cycle ID must not be blank" }
        require(collectionId.isNotBlank()) { "Collection ID must not be blank" }
        require(shuffleSeed.isNotBlank()) { "Shuffle seed must not be blank" }
        require(createdAtMillis >= 0) { "Cycle creation time must be nonnegative" }
    }
}

data class PlaylistCycleItem(
    val cycleId: String,
    val ordinal: Int,
    val assetKey: AssetKey,
) {
    init {
        require(cycleId.isNotBlank()) { "Cycle ID must not be blank" }
        require(ordinal >= 0) { "Cycle ordinal must be nonnegative" }
    }
}

data class ConsumedPortraitPartner(
    val cycleId: String,
    val assetKey: AssetKey,
) {
    init {
        require(cycleId.isNotBlank()) { "Cycle ID must not be blank" }
    }
}

data class SlideshowSession(
    val collectionId: String,
    val cycleId: String,
    val currentOrdinal: Int,
    val currentAssetKey: AssetKey,
    val lastPresentedAssetKey: AssetKey?,
) {
    init {
        require(collectionId.isNotBlank()) { "Collection ID must not be blank" }
        require(cycleId.isNotBlank()) { "Cycle ID must not be blank" }
        require(currentOrdinal >= 0) { "Current ordinal must be nonnegative" }
    }
}

class CycleSnapshot(
    val cycle: PlaylistCycle,
    items: List<PlaylistCycleItem>,
    consumedPartners: List<ConsumedPortraitPartner>,
    val session: SlideshowSession,
) {
    val items: List<PlaylistCycleItem> =
        Collections.unmodifiableList(ArrayList(items))
    val consumedPartners: List<ConsumedPortraitPartner> =
        Collections.unmodifiableList(ArrayList(consumedPartners))

    init {
        require(items.isNotEmpty()) { "Cycle snapshot must contain at least one item" }
        require(items.all { it.cycleId == cycle.id }) {
            "Every cycle item must belong to the snapshot cycle"
        }
        require(consumedPartners.all { it.cycleId == cycle.id }) {
            "Every consumed partner must belong to the snapshot cycle"
        }
        require(session.cycleId == cycle.id) {
            "Session must belong to the snapshot cycle"
        }
        require(session.collectionId == cycle.collectionId) {
            "Session and cycle must belong to the same collection"
        }
    }

    fun copy(
        cycle: PlaylistCycle = this.cycle,
        items: List<PlaylistCycleItem> = this.items,
        consumedPartners: List<ConsumedPortraitPartner> = this.consumedPartners,
        session: SlideshowSession = this.session,
    ): CycleSnapshot = CycleSnapshot(
        cycle = cycle,
        items = items,
        consumedPartners = consumedPartners,
        session = session,
    )
}

class SafConnection(
    val profileId: SourceProfileId,
    val treeUri: String,
) {
    init {
        require(treeUri.isNotBlank()) { "Tree URI must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is SafConnection &&
            profileId == other.profileId &&
            treeUri == other.treeUri

    override fun hashCode(): Int =
        listOf(profileId, treeUri).hashCode()

    override fun toString(): String =
        "SafConnection(profileId=$profileId, treeUri=<redacted>)"
}


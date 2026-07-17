package com.jedon.kellikanvas.catalog

import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId

data class SourceProfile(
    val id: SourceProfileId,
    val kind: SourceKind,
    val displayName: String,
    val createdAtMillis: Long,
) {
    init {
        require(displayName.isNotBlank()) { "Source display name must not be blank" }
        require(createdAtMillis >= 0) { "Source creation time must be nonnegative" }
    }
}

data class SelectedRoot(
    val profileId: SourceProfileId,
    val objectId: ProviderObjectId,
    val recursive: Boolean,
)

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
    val createdAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Cycle ID must not be blank" }
        require(collectionId.isNotBlank()) { "Collection ID must not be blank" }
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

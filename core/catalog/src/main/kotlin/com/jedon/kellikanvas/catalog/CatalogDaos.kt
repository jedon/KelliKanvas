package com.jedon.kellikanvas.catalog

import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId

class SourceProfileDao internal constructor(
    private val roomDao: RoomSourceProfileDao,
) {
    suspend fun upsert(profile: SourceProfile) = roomDao.upsert(profile.toEntity())

    suspend fun get(id: SourceProfileId): SourceProfile? = roomDao.get(id.value)?.toDomain()

    suspend fun delete(id: SourceProfileId) = roomDao.delete(id.value)
}

class SelectedRootDao internal constructor(
    private val roomDao: RoomSelectedRootDao,
) {
    suspend fun upsert(root: SelectedRoot) = roomDao.upsert(root.toEntity())

    suspend fun list(profileId: SourceProfileId): List<SelectedRoot> = roomDao.list(profileId.value).map(SelectedRootEntity::toDomain)
}

class CatalogAssetDao internal constructor(
    private val roomDao: RoomCatalogAssetDao,
) {
    suspend fun upsert(asset: CatalogAsset) = roomDao.upsert(asset.toEntity())

    suspend fun upsertAll(assets: List<CatalogAsset>) = roomDao.upsertAll(assets.map(CatalogAsset::toEntity))

    suspend fun get(key: AssetKey): CatalogAsset? = roomDao.get(key.profileId.value, key.objectId.value)?.toDomain()

    suspend fun delete(key: AssetKey) = roomDao.delete(key.profileId.value, key.objectId.value)
}

class PlaylistCycleDao internal constructor(
    private val roomDao: RoomPlaylistCycleDao,
) {
    suspend fun upsert(cycle: PlaylistCycle) = roomDao.upsert(cycle.toEntity())

    suspend fun get(cycleId: String): PlaylistCycle? = roomDao.get(cycleId)?.toDomain()

    suspend fun delete(cycleId: String) = roomDao.delete(cycleId)
}

class PlaylistCycleItemDao internal constructor(
    private val roomDao: RoomPlaylistCycleItemDao,
) {
    suspend fun insert(item: PlaylistCycleItem) = roomDao.insert(item.toEntity())

    suspend fun list(cycleId: String): List<PlaylistCycleItem> = roomDao.list(cycleId).map(PlaylistCycleItemEntity::toDomain)
}

class ConsumedPortraitPartnerDao internal constructor(
    private val roomDao: RoomConsumedPortraitPartnerDao,
) {
    suspend fun insert(partner: ConsumedPortraitPartner) = roomDao.insert(partner.toEntity())

    suspend fun list(cycleId: String): List<ConsumedPortraitPartner> = roomDao.list(cycleId).map(ConsumedPortraitPartnerEntity::toDomain)
}

class SlideshowSessionDao internal constructor(
    private val roomDao: RoomSlideshowSessionDao,
) {
    suspend fun upsert(session: SlideshowSession) = roomDao.upsert(session.toEntity())

    suspend fun get(collectionId: String): SlideshowSession? = roomDao.get(collectionId)?.toDomain()

    suspend fun delete(collectionId: String) = roomDao.delete(collectionId)
}

private fun SourceProfile.toEntity() = SourceProfileEntity(id.value, kind.name, displayName, createdAtMillis)

private fun SourceProfileEntity.toDomain() = SourceProfile(SourceProfileId(profileId), SourceKind.valueOf(sourceKind), displayName, createdAtMillis)

private fun SelectedRoot.toEntity() = SelectedRootEntity(profileId.value, objectId.value, recursive)

private fun SelectedRootEntity.toDomain() = SelectedRoot(SourceProfileId(profileId), ProviderObjectId(objectId), recursive)

private fun CatalogAsset.toEntity() = CatalogAssetEntity(
    profileId = key.profileId.value,
    objectId = key.objectId.value,
    mimeType = mimeType,
    displayName = displayName,
    width = width,
    height = height,
    captureTimeMillis = captureTimeMillis,
    modifiedAtMillis = modifiedAtMillis,
    versionTag = versionToken,
)

private fun CatalogAssetEntity.toDomain() = CatalogAsset(
    key = assetKey(profileId, objectId),
    mimeType = mimeType,
    displayName = displayName,
    width = width,
    height = height,
    captureTimeMillis = captureTimeMillis,
    modifiedAtMillis = modifiedAtMillis,
    versionToken = versionTag,
)

private fun PlaylistCycle.toEntity() = PlaylistCycleEntity(id, collectionId, createdAtMillis)

private fun PlaylistCycleEntity.toDomain() = PlaylistCycle(cycleId, collectionId, createdAtMillis)

private fun PlaylistCycleItem.toEntity() = PlaylistCycleItemEntity(
    cycleId,
    ordinal,
    assetKey.profileId.value,
    assetKey.objectId.value,
)

private fun PlaylistCycleItemEntity.toDomain() = PlaylistCycleItem(cycleId, ordinal, assetKey(profileId, objectId))

private fun ConsumedPortraitPartner.toEntity() = ConsumedPortraitPartnerEntity(
    cycleId,
    assetKey.profileId.value,
    assetKey.objectId.value,
)

private fun ConsumedPortraitPartnerEntity.toDomain() = ConsumedPortraitPartner(cycleId, assetKey(profileId, objectId))

private fun SlideshowSession.toEntity() = SlideshowSessionEntity(
    collectionId = collectionId,
    cycleId = cycleId,
    currentOrdinal = currentOrdinal,
    currentProfileId = currentAssetKey.profileId.value,
    currentObjectId = currentAssetKey.objectId.value,
    lastProfileId = lastPresentedAssetKey?.profileId?.value,
    lastObjectId = lastPresentedAssetKey?.objectId?.value,
)

private fun SlideshowSessionEntity.toDomain(): SlideshowSession {
    check((lastProfileId == null) == (lastObjectId == null)) {
        "Last-presented asset key must be fully present or absent"
    }
    return SlideshowSession(
        collectionId = collectionId,
        cycleId = cycleId,
        currentOrdinal = currentOrdinal,
        currentAssetKey = assetKey(currentProfileId, currentObjectId),
        lastPresentedAssetKey =
        lastProfileId?.let { profileId ->
            assetKey(profileId, requireNotNull(lastObjectId))
        },
    )
}

private fun assetKey(
    profileId: String,
    objectId: String,
) = AssetKey(SourceProfileId(profileId), ProviderObjectId(objectId))

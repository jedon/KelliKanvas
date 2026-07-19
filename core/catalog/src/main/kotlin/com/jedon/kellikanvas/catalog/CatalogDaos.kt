package com.jedon.kellikanvas.catalog

import androidx.room.withTransaction
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

class SafConnectionDao internal constructor(
    private val roomDao: RoomSafConnectionDao,
) {
    suspend fun upsert(connection: SafConnection) = roomDao.upsert(connection.toEntity())

    suspend fun get(id: SourceProfileId): SafConnection? = roomDao.get(id.value)?.toDomain()

    suspend fun delete(id: SourceProfileId) = roomDao.delete(id.value)
}

class DlnaConnectionDao internal constructor(
    private val roomDao: RoomDlnaConnectionDao,
) {
    suspend fun upsert(connection: DlnaConnection) = roomDao.upsert(connection.toEntity())

    suspend fun get(id: SourceProfileId): DlnaConnection? = roomDao.get(id.value)?.toDomain()

    suspend fun delete(id: SourceProfileId) = roomDao.delete(id.value)
}

class SmbConnectionDao internal constructor(
    private val roomDao: RoomSmbConnectionDao,
) {
    suspend fun upsert(connection: SmbConnection) = roomDao.upsert(connection.toEntity())

    suspend fun get(id: SourceProfileId): SmbConnection? = roomDao.get(id.value)?.toDomain()

    suspend fun delete(id: SourceProfileId) = roomDao.delete(id.value)
}

class CollectionDao internal constructor(
    private val roomDao: RoomCollectionDao,
) {
    suspend fun upsert(collection: CatalogCollection) = roomDao.upsert(collection.toEntity())

    suspend fun get(collectionId: String): CatalogCollection? = roomDao.get(collectionId)?.toDomain()

    suspend fun list(): List<CatalogCollection> = roomDao.list().map(CatalogCollectionEntity::toDomain)
}

class SelectedRootDao internal constructor(
    private val database: KelliKanvasDatabase,
    private val roomDao: RoomSelectedRootDao,
) {
    suspend fun replace(root: SelectedRoot) {
        database.withTransaction {
            val entity = root.toEntity()
            roomDao.upsert(entity)
            roomDao.deleteFilters(entity.collectionId, entity.profileId, entity.objectId)
            roomDao.insertFilters(root.toFilterEntities())
        }
    }

    suspend fun replaceAllForCollection(
        collectionId: String,
        roots: List<SelectedRoot>,
    ) {
        require(roots.all { it.collectionId == collectionId }) {
            "All selected roots must belong to the target collection"
        }
        database.withTransaction {
            roomDao.deleteAllFilters(collectionId)
            roomDao.deleteAll(collectionId)
            for (root in roots) {
                val entity = root.toEntity()
                roomDao.upsert(entity)
                roomDao.insertFilters(root.toFilterEntities())
            }
        }
    }

    suspend fun list(collectionId: String): List<SelectedRoot> = roomDao.listAggregates(collectionId).map { aggregate ->
        aggregate.root.toDomain(
            aggregate.filters.mapTo(
                linkedSetOf(),
                SelectedRootFilterEntity::filterValue,
            ),
        )
    }

    suspend fun delete(
        collectionId: String,
        profileId: SourceProfileId,
        objectId: ProviderObjectId,
    ) {
        database.withTransaction {
            roomDao.deleteFilters(collectionId, profileId.value, objectId.value)
            roomDao.deleteRoot(collectionId, profileId.value, objectId.value)
        }
    }
}

class CatalogAssetDao internal constructor(
    private val roomDao: RoomCatalogAssetDao,
) {
    suspend fun upsert(asset: CatalogAsset) = roomDao.upsert(asset.toEntity())

    suspend fun upsertAll(assets: List<CatalogAsset>) = roomDao.upsertAll(assets.map(CatalogAsset::toEntity))

    suspend fun get(key: AssetKey): CatalogAsset? = roomDao.get(key.profileId.value, key.objectId.value)?.toDomain()

    suspend fun delete(key: AssetKey) = roomDao.delete(key.profileId.value, key.objectId.value)
}

internal class PlaylistCycleDao internal constructor(
    private val roomDao: RoomPlaylistCycleDao,
) {
    suspend fun insert(cycle: PlaylistCycle) = roomDao.insert(cycle.toEntity())

    suspend fun get(cycleId: String): PlaylistCycle? = roomDao.get(cycleId)?.toDomain()

    suspend fun delete(cycleId: String) = roomDao.delete(cycleId)
}

internal class PlaylistCycleItemDao internal constructor(
    private val roomDao: RoomPlaylistCycleItemDao,
) {
    suspend fun insert(item: PlaylistCycleItem) = roomDao.insert(item.toEntity())

    suspend fun list(cycleId: String): List<PlaylistCycleItem> = roomDao.list(cycleId).map(PlaylistCycleItemEntity::toDomain)
}

internal class ConsumedPortraitPartnerDao internal constructor(
    private val roomDao: RoomConsumedPortraitPartnerDao,
) {
    suspend fun insert(partner: ConsumedPortraitPartner) = roomDao.insert(partner.toEntity())

    suspend fun list(cycleId: String): List<ConsumedPortraitPartner> = roomDao.list(cycleId).map(ConsumedPortraitPartnerEntity::toDomain)
}

class SlideshowSessionDao internal constructor(
    private val roomDao: RoomSlideshowSessionDao,
) {
    suspend fun upsert(session: SlideshowSession) = roomDao.persist(session.toEntity(), session.toLastPresentedEntity())

    suspend fun get(collectionId: String): SlideshowSession? = roomDao.getAggregate(collectionId)?.toDomain()

    suspend fun delete(collectionId: String) = roomDao.delete(collectionId)
}

class CycleSnapshotDao internal constructor(
    private val database: KelliKanvasDatabase,
) {
    suspend fun persist(snapshot: CycleSnapshot) {
        database.withTransaction {
            database.roomPlaylistCycles().insert(snapshot.cycle.toEntity())
            database.roomPlaylistCycleItems().insertAll(
                snapshot.items.map(PlaylistCycleItem::toEntity),
            )
            database.roomConsumedPortraitPartners().insertAll(
                snapshot.consumedPartners.map(ConsumedPortraitPartner::toEntity),
            )
            database.roomSlideshowSessions().persist(
                snapshot.session.toEntity(),
                snapshot.session.toLastPresentedEntity(),
            )
        }
    }
}

private fun SourceProfile.toEntity() = SourceProfileEntity(
    profileId = id.value,
    sourceKindCode = kind.toStableCode(),
    displayName = displayName,
    statusCode = status.toStableCode(),
    lastSuccessfulRefreshMillis = lastSuccessfulRefreshMillis,
    createdAtMillis = createdAtMillis,
)

private fun SourceProfileEntity.toDomain() = SourceProfile(
    id = SourceProfileId(profileId),
    kind = sourceKindFromStableCode(sourceKindCode),
    displayName = displayName,
    status = sourceStatusFromStableCode(statusCode),
    lastSuccessfulRefreshMillis = lastSuccessfulRefreshMillis,
    createdAtMillis = createdAtMillis,
)

private fun SourceProfileKind.toStableCode(): String = when (this) {
    is SourceProfileKind.Unknown -> stableCode
    is SourceProfileKind.Known ->
        when (value) {
            SourceKind.DLNA -> "dlna_v1"
            SourceKind.SMB -> "smb_v1"
            SourceKind.SAF -> "saf_v1"
            SourceKind.HTTP -> "http_v1"
        }
}

private fun sourceKindFromStableCode(code: String): SourceProfileKind = when (code) {
    "dlna_v1" -> SourceProfileKind.Known(SourceKind.DLNA)
    "smb_v1" -> SourceProfileKind.Known(SourceKind.SMB)
    "saf_v1" -> SourceProfileKind.Known(SourceKind.SAF)
    "http_v1" -> SourceProfileKind.Known(SourceKind.HTTP)
    else -> SourceProfileKind.Unknown(code)
}

private fun SourceProfileStatus.toStableCode(): String = when (this) {
    SourceProfileStatus.UNKNOWN -> "unknown"
    SourceProfileStatus.AVAILABLE -> "available"
    SourceProfileStatus.UNAVAILABLE -> "unavailable"
    SourceProfileStatus.REQUIRES_REPAIR -> "requires_repair"
}

private fun sourceStatusFromStableCode(code: String): SourceProfileStatus = when (code) {
    "available" -> SourceProfileStatus.AVAILABLE
    "unavailable" -> SourceProfileStatus.UNAVAILABLE
    "requires_repair" -> SourceProfileStatus.REQUIRES_REPAIR
    else -> SourceProfileStatus.UNKNOWN
}

private fun CatalogCollection.toEntity() = CatalogCollectionEntity(
    collectionId = id,
    label = label,
    indexStatusCode = indexStatus.toStableCode(),
    lastIndexedAtMillis = lastIndexedAtMillis,
)

private fun CatalogCollectionEntity.toDomain() = CatalogCollection(
    id = collectionId,
    label = label,
    indexStatus = collectionIndexStatusFromStableCode(indexStatusCode),
    lastIndexedAtMillis = lastIndexedAtMillis,
)

private fun CollectionIndexStatus.toStableCode(): String = when (this) {
    CollectionIndexStatus.NOT_INDEXED -> "not_indexed"
    CollectionIndexStatus.INDEXING -> "indexing"
    CollectionIndexStatus.READY -> "ready"
    CollectionIndexStatus.FAILED -> "failed"
    CollectionIndexStatus.UNKNOWN -> "unknown"
}

private fun collectionIndexStatusFromStableCode(code: String): CollectionIndexStatus = when (code) {
    "not_indexed" -> CollectionIndexStatus.NOT_INDEXED
    "indexing" -> CollectionIndexStatus.INDEXING
    "ready" -> CollectionIndexStatus.READY
    "failed" -> CollectionIndexStatus.FAILED
    else -> CollectionIndexStatus.UNKNOWN
}

private fun SelectedRoot.toEntity() = SelectedRootEntity(
    collectionId = collectionId,
    profileId = profileId.value,
    objectId = objectId.value,
    displayLabel = displayLabel,
    includeDescendants = includeDescendants,
)

private fun SelectedRoot.toFilterEntities(): List<SelectedRootFilterEntity> = fileTypeFilters.map { filter ->
    SelectedRootFilterEntity(
        collectionId = collectionId,
        profileId = profileId.value,
        objectId = objectId.value,
        filterValue = filter,
    )
}

private fun SelectedRootEntity.toDomain(filters: Set<String>) = SelectedRoot(
    collectionId = collectionId,
    profileId = SourceProfileId(profileId),
    objectId = ProviderObjectId(objectId),
    displayLabel = displayLabel,
    includeDescendants = includeDescendants,
    fileTypeFilters = filters,
)

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

private fun PlaylistCycle.toEntity() = PlaylistCycleEntity(id, collectionId, shuffleSeed, createdAtMillis)

private fun PlaylistCycleEntity.toDomain() = PlaylistCycle(cycleId, collectionId, shuffleSeed, createdAtMillis)

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
)

private fun SlideshowSession.toLastPresentedEntity(): SlideshowSessionLastPresentedEntity? = lastPresentedAssetKey?.let { last ->
    SlideshowSessionLastPresentedEntity(
        collectionId = collectionId,
        profileId = last.profileId.value,
        objectId = last.objectId.value,
    )
}

private fun SlideshowSessionAggregate.toDomain() = SlideshowSession(
    collectionId = session.collectionId,
    cycleId = session.cycleId,
    currentOrdinal = session.currentOrdinal,
    currentAssetKey =
    assetKey(
        session.currentProfileId,
        session.currentObjectId,
    ),
    lastPresentedAssetKey =
    lastPresented?.let {
        assetKey(it.profileId, it.objectId)
    },
)

private fun assetKey(
    profileId: String,
    objectId: String,
) = AssetKey(SourceProfileId(profileId), ProviderObjectId(objectId))

private fun SafConnection.toEntity() = SafConnectionEntity(
    profileId = profileId.value,
    treeUri = treeUri,
)

private fun SafConnectionEntity.toDomain() = SafConnection(
    profileId = SourceProfileId(profileId),
    treeUri = treeUri,
)

private fun DlnaConnection.toEntity() = DlnaConnectionEntity(
    profileId = profileId.value,
    serverUdn = serverUdn,
    descriptionLocation = descriptionLocation,
    controlUrl = controlUrl,
    contentDirectoryVersion = contentDirectoryVersion,
    displayName = displayName,
)

private fun DlnaConnectionEntity.toDomain() = DlnaConnection(
    profileId = SourceProfileId(profileId),
    serverUdn = serverUdn,
    descriptionLocation = descriptionLocation,
    controlUrl = controlUrl,
    contentDirectoryVersion = contentDirectoryVersion,
    displayName = displayName,
)

private fun SmbConnection.toEntity() = SmbConnectionEntity(
    profileId = profileId.value,
    host = host,
    port = port,
    share = share,
    domain = domain,
    username = username,
    displayName = displayName,
)

private fun SmbConnectionEntity.toDomain() = SmbConnection(
    profileId = SourceProfileId(profileId),
    host = host,
    port = port,
    share = share,
    domain = domain,
    username = username,
    displayName = displayName,
)


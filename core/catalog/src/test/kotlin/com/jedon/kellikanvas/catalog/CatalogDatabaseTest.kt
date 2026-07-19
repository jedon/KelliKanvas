package com.jedon.kellikanvas.catalog

import android.database.sqlite.SQLiteConstraintException
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogDatabaseTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun createDatabase() {
        database =
            KelliKanvasDatabaseFactory.inMemory(
                RuntimeEnvironment.getApplication(),
            )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `source upsert and selected roots round trip through domain models`() = runTest {
        val profile =
            SourceProfile(
                id = SourceProfileId("family-nas"),
                kind = SourceKind.SMB,
                displayName = "Family NAS",
                createdAtMillis = 10,
            )
        val root =
            SelectedRoot(
                collectionId = "collection-1",
                profileId = profile.id,
                objectId = ProviderObjectId("photos/holidays"),
                displayLabel = "Holidays",
                includeDescendants = true,
            )

        database.sourceProfiles.upsert(profile)
        database.collections.upsert(CatalogCollection("collection-1", "Collection"))
        database.selectedRoots.replace(root)

        assertThat(database.sourceProfiles.get(profile.id)).isEqualTo(profile)
        assertThat(database.selectedRoots.list("collection-1")).containsExactly(root)
    }

    @Test
    fun `deleting a source cascades its selected roots`() = runTest {
        val profile = sourceProfile()
        database.sourceProfiles.upsert(profile)
        database.collections.upsert(CatalogCollection("collection-1", "Collection"))
        database.selectedRoots.replace(
            SelectedRoot(
                "collection-1",
                profile.id,
                ProviderObjectId("root"),
                "Root",
                includeDescendants = false,
            ),
        )

        database.sourceProfiles.delete(profile.id)

        assertThat(database.selectedRoots.list("collection-1")).isEmpty()
    }

    @Test
    fun `catalog asset metadata upsert replaces mutable metadata`() = runTest {
        val key = assetKey("photo-1")
        database.sourceProfiles.upsert(sourceProfile())
        database.catalogAssets.upsert(
            CatalogAsset(key, "image/jpeg", "old.jpg", 100, 200, 1, 2, "v1"),
        )

        val replacement =
            CatalogAsset(key, "image/jpeg", "new.jpg", 300, 400, 3, 4, "v2")
        database.catalogAssets.upsert(replacement)

        assertThat(database.catalogAssets.get(key)).isEqualTo(replacement)
    }

    @Test
    fun `cycle ordinals and assets are unique within a cycle`() = runTest {
        val first = catalogAsset("photo-1")
        val second = catalogAsset("photo-2")
        prepareCollection()
        database.catalogAssets.upsertAll(listOf(first, second))
        database.cycleSnapshots.persist(
            CycleSnapshot(
                cycle = PlaylistCycle("cycle-1", "collection-1", "seed", 20),
                items = listOf(PlaylistCycleItem("cycle-1", 0, first.key)),
                consumedPartners = emptyList(),
                session =
                SlideshowSession(
                    collectionId = "collection-1",
                    cycleId = "cycle-1",
                    currentOrdinal = 0,
                    currentAssetKey = first.key,
                    lastPresentedAssetKey = null,
                ),
            ),
        )

        // Schema uniqueness is enforced even on module-internal piecemeal inserts.
        val duplicateOrdinal =
            runCatching {
                database.playlistCycleItems.insert(
                    PlaylistCycleItem("cycle-1", 0, second.key),
                )
            }.exceptionOrNull()
        val duplicateAsset =
            runCatching {
                database.playlistCycleItems.insert(
                    PlaylistCycleItem("cycle-1", 1, first.key),
                )
            }.exceptionOrNull()

        assertThat(duplicateOrdinal).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(duplicateAsset).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `catalog assets cannot be removed while an active cycle references them`() = runTest {
        val asset = catalogAsset("photo-1")
        prepareCollection()
        database.catalogAssets.upsert(asset)
        database.cycleSnapshots.persist(
            CycleSnapshot(
                cycle = PlaylistCycle("cycle-1", "collection-1", "seed", 20),
                items = listOf(PlaylistCycleItem("cycle-1", 0, asset.key)),
                consumedPartners = emptyList(),
                session =
                SlideshowSession(
                    collectionId = "collection-1",
                    cycleId = "cycle-1",
                    currentOrdinal = 0,
                    currentAssetKey = asset.key,
                    lastPresentedAssetKey = null,
                ),
            ),
        )

        val failure = runCatching { database.catalogAssets.delete(asset.key) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.catalogAssets.get(asset.key)).isEqualTo(asset)
    }

    private fun sourceProfile() = SourceProfile(
        id = SourceProfileId("source-1"),
        kind = SourceKind.SAF,
        displayName = "USB",
        createdAtMillis = 1,
    )

    private suspend fun prepareCollection() {
        database.sourceProfiles.upsert(sourceProfile())
        database.collections.upsert(CatalogCollection("collection-1", "Collection"))
    }

    private fun assetKey(objectId: String) = AssetKey(SourceProfileId("source-1"), ProviderObjectId(objectId))

    private fun catalogAsset(objectId: String) = CatalogAsset(
        key = assetKey(objectId),
        mimeType = "image/jpeg",
        displayName = "$objectId.jpg",
        width = 100,
        height = 200,
        captureTimeMillis = null,
        modifiedAtMillis = 10,
        versionToken = null,
    )
}

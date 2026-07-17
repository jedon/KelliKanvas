package com.jedon.kellikanvas.catalog

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDaoInstrumentationTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun createDatabase() {
        database =
            KelliKanvasDatabaseFactory.inMemory(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun sourceCollectionAndRootRoundTrip() = runBlocking {
        val source = source()
        val collection = collection()
        val root =
            SelectedRoot(
                collection.id,
                source.id,
                ProviderObjectId("photos"),
                "Photos",
                includeDescendants = true,
                fileTypeFilters = setOf("image/jpeg"),
            )

        database.sourceProfiles.upsert(source)
        database.collections.upsert(collection)
        database.selectedRoots.replace(root)

        assertThat(database.sourceProfiles.get(source.id)).isEqualTo(source)
        assertThat(database.collections.get(collection.id)).isEqualTo(collection)
        assertThat(database.selectedRoots.list(collection.id)).containsExactly(root)
    }

    @Test
    fun snapshotAndSessionRoundTrip() = runBlocking {
        prepareCatalog()
        val snapshot = snapshot(lastPresentedAssetKey = key("photo-2"))

        database.cycleSnapshots.persist(snapshot)

        assertThat(database.playlistCycles.get(snapshot.cycle.id)).isEqualTo(snapshot.cycle)
        assertThat(database.slideshowSessions.get(snapshot.session.collectionId))
            .isEqualTo(snapshot.session)
    }

    @Test
    fun invalidSnapshotRollsBackTransaction() = runBlocking {
        prepareCatalog()
        val invalid = snapshot(lastPresentedAssetKey = key("missing"))

        val failure = runCatching { database.cycleSnapshots.persist(invalid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.playlistCycles.get("cycle-1")).isNull()
        assertThat(database.playlistCycleItems.list("cycle-1")).isEmpty()
        assertThat(database.slideshowSessions.get("living-room")).isNull()
    }

    private suspend fun prepareCatalog() {
        database.sourceProfiles.upsert(source())
        database.collections.upsert(collection())
        database.catalogAssets.upsertAll(listOf(asset("photo-1"), asset("photo-2")))
    }

    private fun source() = SourceProfile(
        SourceProfileId("source-1"),
        SourceKind.SAF,
        "USB",
        createdAtMillis = 1,
    )

    private fun collection() = CatalogCollection("living-room", "Living Room")

    private fun snapshot(lastPresentedAssetKey: AssetKey?) = CycleSnapshot(
        cycle = PlaylistCycle("cycle-1", "living-room", "seed", 1),
        items =
        listOf(
            PlaylistCycleItem("cycle-1", 0, key("photo-1")),
            PlaylistCycleItem("cycle-1", 1, key("photo-2")),
        ),
        consumedPartners =
        listOf(
            ConsumedPortraitPartner("cycle-1", key("photo-2")),
        ),
        session =
        SlideshowSession(
            collectionId = "living-room",
            cycleId = "cycle-1",
            currentOrdinal = 0,
            currentAssetKey = key("photo-1"),
            lastPresentedAssetKey = lastPresentedAssetKey,
        ),
    )

    private fun asset(objectId: String) = CatalogAsset(
        key = key(objectId),
        mimeType = "image/jpeg",
        displayName = "$objectId.jpg",
        width = 100,
        height = 200,
        captureTimeMillis = null,
        modifiedAtMillis = 1,
        versionToken = null,
    )

    private fun key(objectId: String) = AssetKey(SourceProfileId("source-1"), ProviderObjectId(objectId))
}

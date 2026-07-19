package com.jedon.kellikanvas.catalog

import android.database.sqlite.SQLiteConstraintException
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetKey
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class CycleSnapshotTransactionTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun createDatabase() = runTest {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        database.sourceProfiles.upsert(
            SourceProfile(
                SourceProfileId("source-1"),
                SourceKind.SAF,
                "USB",
                SourceProfileStatus.AVAILABLE,
                1,
                1,
            ),
        )
        database.collections.upsert(
            CatalogCollection("living-room", "Living Room"),
        )
        database.catalogAssets.upsertAll(
            listOf(asset("photo-1"), asset("photo-2"), asset("photo-3")),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `cycleSnapshots persist is the supported atomic write path for cycles`() = runTest {
        val snapshot = snapshot()

        // Public facade: only cycleSnapshots (and slideshowSessions) write cycles.
        // playlistCycles / playlistCycleItems / consumedPortraitPartners are internal.
        database.cycleSnapshots.persist(snapshot)

        assertThat(database.playlistCycles.get(snapshot.cycle.id)).isEqualTo(snapshot.cycle)
        assertThat(database.playlistCycleItems.list(snapshot.cycle.id))
            .containsExactlyElementsIn(snapshot.items)
            .inOrder()
        assertThat(database.consumedPortraitPartners.list(snapshot.cycle.id))
            .containsExactlyElementsIn(snapshot.consumedPartners)
        assertThat(database.slideshowSessions.get(snapshot.session.collectionId))
            .isEqualTo(snapshot.session)
    }

    @Test
    fun `cycle snapshot defensively captures immutable item lists`() {
        val mutableItems = snapshot().items.toMutableList()
        val mutablePartners = snapshot().consumedPartners.toMutableList()
        val captured =
            snapshot().copy(
                items = mutableItems,
                consumedPartners = mutablePartners,
            )

        mutableItems.clear()
        mutablePartners.clear()

        assertThat(captured.items).hasSize(2)
        assertThat(captured.consumedPartners).hasSize(1)
    }

    @Test
    fun `snapshot rolls back when consumed partner is not a cycle member`() = runTest {
        val invalid =
            snapshot().copy(
                consumedPartners =
                listOf(
                    ConsumedPortraitPartner("cycle-1", key("photo-3")),
                ),
            )

        val failure = runCatching { database.cycleSnapshots.persist(invalid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.playlistCycles.get("cycle-1")).isNull()
        assertThat(database.playlistCycleItems.list("cycle-1")).isEmpty()
        assertThat(database.slideshowSessions.get("living-room")).isNull()
    }

    @Test
    fun `snapshot rolls back when current session asset does not match cycle ordinal`() = runTest {
        val invalid =
            snapshot().copy(
                session =
                SlideshowSession(
                    collectionId = "living-room",
                    cycleId = "cycle-1",
                    currentOrdinal = 0,
                    currentAssetKey = key("photo-2"),
                    lastPresentedAssetKey = null,
                ),
            )

        val failure = runCatching { database.cycleSnapshots.persist(invalid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.playlistCycles.get("cycle-1")).isNull()
    }

    @Test
    fun `snapshot rolls back when last presented asset does not exist`() = runTest {
        val invalid =
            snapshot().copy(
                session =
                snapshot().session.copy(
                    lastPresentedAssetKey = key("missing-photo"),
                ),
            )

        val failure = runCatching { database.cycleSnapshots.persist(invalid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.playlistCycles.get("cycle-1")).isNull()
        assertThat(database.slideshowSessions.get("living-room")).isNull()
    }

    @Test
    fun `established cycle header cannot be mutated`() = runTest {
        val original = snapshot()
        database.cycleSnapshots.persist(original)

        val failure =
            runCatching {
                database.playlistCycles.insert(
                    original.cycle.copy(shuffleSeed = "different-seed"),
                )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(database.playlistCycles.get("cycle-1")).isEqualTo(original.cycle)
    }

    @Test
    fun `concurrent creation leaves one complete immutable cycle`() = runTest {
        val attempts =
            listOf("seed-a", "seed-b").map { seed ->
                async {
                    runCatching {
                        database.cycleSnapshots.persist(
                            snapshot().copy(cycle = snapshot().cycle.copy(shuffleSeed = seed)),
                        )
                    }
                }
            }.awaitAll()

        assertThat(attempts.count { it.isSuccess }).isEqualTo(1)
        assertThat(database.playlistCycleItems.list("cycle-1")).hasSize(2)
        assertThat(database.slideshowSessions.get("living-room")).isNotNull()
    }

    private fun snapshot() = CycleSnapshot(
        cycle =
        PlaylistCycle(
            id = "cycle-1",
            collectionId = "living-room",
            shuffleSeed = "seed-1",
            createdAtMillis = 20,
        ),
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
            lastPresentedAssetKey = null,
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

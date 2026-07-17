package com.jedon.kellikanvas.catalog

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
class SlideshowStateTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun createDatabase() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `slideshow session round trips collection cycle ordinal and asset keys`() = runTest {
        prepareSourceAndCollection()
        database.catalogAssets.upsertAll(
            listOf(catalogAsset("current"), catalogAsset("last")),
        )
        database.playlistCycles.insert(PlaylistCycle("cycle-7", "living-room", "seed", 1))
        database.playlistCycleItems.insert(
            PlaylistCycleItem("cycle-7", 3, assetKey("current")),
        )
        val session =
            SlideshowSession(
                collectionId = "living-room",
                cycleId = "cycle-7",
                currentOrdinal = 3,
                currentAssetKey = assetKey("current"),
                lastPresentedAssetKey = assetKey("last"),
            )

        database.slideshowSessions.upsert(session)

        assertThat(database.slideshowSessions.get("living-room")).isEqualTo(session)
    }

    @Test
    fun `consumed portrait partners are unique and round trip`() = runTest {
        val partner = ConsumedPortraitPartner("cycle-7", assetKey("partner"))
        prepareSourceAndCollection()
        database.catalogAssets.upsert(catalogAsset("partner"))
        database.playlistCycles.insert(PlaylistCycle("cycle-7", "living-room", "seed", 1))
        database.playlistCycleItems.insert(
            PlaylistCycleItem("cycle-7", 0, assetKey("partner")),
        )

        database.consumedPortraitPartners.insert(partner)
        database.consumedPortraitPartners.insert(partner)

        assertThat(database.consumedPortraitPartners.list("cycle-7"))
            .containsExactly(partner)
    }

    @Test
    fun `room schema contains metadata only and no blob or secret columns`() {
        val forbiddenNameParts = listOf("password", "token", "secret", "credential", "cache", "path")
        val sqlite = database.openHelper.readableDatabase
        val tableCursor =
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            )

        tableCursor.use {
            while (it.moveToNext()) {
                val tableName = it.getString(0)
                sqlite.query("PRAGMA table_info(`$tableName`)").use { columns ->
                    val nameIndex = columns.getColumnIndexOrThrow("name")
                    val typeIndex = columns.getColumnIndexOrThrow("type")
                    while (columns.moveToNext()) {
                        val name = columns.getString(nameIndex).lowercase()
                        val type = columns.getString(typeIndex).uppercase()
                        assertThat(type).isNotEqualTo("BLOB")
                        assertThat(forbiddenNameParts.none(name::contains)).isTrue()
                    }
                }
            }
        }
    }

    private fun assetKey(objectId: String) = AssetKey(SourceProfileId("source-1"), ProviderObjectId(objectId))

    private suspend fun prepareSourceAndCollection() {
        database.sourceProfiles.upsert(
            SourceProfile(
                SourceProfileId("source-1"),
                SourceKind.SAF,
                "USB",
                createdAtMillis = 1,
            ),
        )
        database.collections.upsert(CatalogCollection("living-room", "Living Room"))
    }

    private fun catalogAsset(objectId: String) = CatalogAsset(
        key = assetKey(objectId),
        mimeType = "image/jpeg",
        displayName = "$objectId.jpg",
        width = 100,
        height = 200,
        captureTimeMillis = null,
        modifiedAtMillis = 1,
        versionToken = null,
    )
}

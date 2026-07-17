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
class CatalogIntegrityTest {
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
    fun `catalog assets require an existing source profile`() = runTest {
        val failure =
            runCatching {
                database.catalogAssets.upsert(asset("missing", "photo-1"))
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `collection and selected root persist approved root shape`() = runTest {
        val source = source("source-1")
        val collection =
            CatalogCollection(
                id = "living-room",
                label = "Living Room",
                indexStatus = CollectionIndexStatus.READY,
                lastIndexedAtMillis = 55,
            )
        val root =
            SelectedRoot(
                collectionId = collection.id,
                profileId = source.id,
                objectId = ProviderObjectId("family"),
                displayLabel = "Family",
                includeDescendants = true,
                fileTypeFilters = setOf("image/jpeg", "image/png"),
            )

        database.sourceProfiles.upsert(source)
        database.collections.upsert(collection)
        database.selectedRoots.replace(root)

        assertThat(database.collections.get(collection.id)).isEqualTo(collection)
        assertThat(database.selectedRoots.list(collection.id)).containsExactly(root)
    }

    @Test
    fun `source kind codes are stable and unknown codes remain readable`() = runTest {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO source_profiles(
                profile_id, source_kind_code, display_name, status_code,
                last_successful_refresh_millis, created_at_millis
            ) VALUES('future-source', 'future_v9', 'Future', 'unknown', NULL, 1)
            """.trimIndent(),
        )

        val restored = database.sourceProfiles.get(SourceProfileId("future-source"))

        assertThat(restored?.kind).isEqualTo(SourceProfileKind.Unknown("future_v9"))
    }

    @Test
    fun `database closes and reopens with persisted metadata`() = runTest {
        database.close()
        val context = RuntimeEnvironment.getApplication()
        val name = "catalog-reopen-test.db"
        context.deleteDatabase(name)
        val first = KelliKanvasDatabaseFactory.create(context, name)
        val source = source("source-1")
        first.sourceProfiles.upsert(source)
        first.close()

        database = KelliKanvasDatabaseFactory.create(context, name)

        assertThat(database.sourceProfiles.get(source.id)).isEqualTo(source)
        context.deleteDatabase(name)
    }

    private fun source(id: String) = SourceProfile(
        id = SourceProfileId(id),
        kind = SourceKind.SAF,
        displayName = "USB",
        status = SourceProfileStatus.AVAILABLE,
        lastSuccessfulRefreshMillis = 10,
        createdAtMillis = 1,
    )

    private fun asset(
        sourceId: String,
        objectId: String,
    ) = CatalogAsset(
        key = AssetKey(SourceProfileId(sourceId), ProviderObjectId(objectId)),
        mimeType = "image/jpeg",
        displayName = "$objectId.jpg",
        width = 100,
        height = 200,
        captureTimeMillis = null,
        modifiedAtMillis = 1,
        versionToken = null,
    )
}

package com.jedon.kellikanvas.google

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.feature.collection.CollectionHubController
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoogleSourceControllerTest {
    private lateinit var database: KelliKanvasDatabase
    private val id = SourceProfileId("drive-account")

    @Before fun setup() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
    }

    @After fun close() {
        database.close()
    }

    @Test fun `adding Drive preserves other sources and removing last folder retires the connection`() = runTest {
        val other = SourceProfileId("nas")
        database.sourceProfiles.upsert(SourceProfile(other, SourceKind.SMB, "NAS", createdAtMillis = 1))
        database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Family"))
        val existing = SelectedRoot(CatalogIds.DEFAULT_COLLECTION_ID, other, ProviderObjectId("photos"), "NAS photos", true)
        database.selectedRoots.replace(existing)
        val controller = GoogleSourceController(database)
        val connection = GoogleConnection(id)
        controller.select(connection, SourceKind.GOOGLE_DRIVE, FolderRef(id, ProviderObjectId("first")), "First album", true)
        controller.select(connection, SourceKind.GOOGLE_DRIVE, FolderRef(id, ProviderObjectId("second")), "Second album", false)
        assertThat(database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID)!!.label).isEqualTo("Family")
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).contains(existing)
        assertThat(database.sourceProfiles.get(id)!!.kind).isEqualTo(SourceProfileKind.Known(SourceKind.GOOGLE_DRIVE))
        var retired: GoogleConnection? = null
        val hub = CollectionHubController(database) { retired = it.googleConnection }
        val roots = hub.listRoots().filter { it.profileId == id }
        hub.removeRoot(roots.first())
        assertThat(database.googleConnections.get(id)).isEqualTo(connection)
        assertThat(retired).isNull()
        hub.removeRoot(roots.last())
        assertThat(database.googleConnections.get(id)).isNull()
        assertThat(database.sourceProfiles.get(id)).isNull()
        assertThat(retired).isEqualTo(connection)
        assertThat(hub.listRoots()).containsExactly(existing)
    }

    @Test fun `canceling an unused Photos setup deletes its metadata but not selected sources`() = runTest {
        val controller = GoogleSourceController(database)
        val connection = GoogleConnection(id, "ambient-device", "https://photos.google.com/device", "client")
        controller.saveConnection(connection, SourceKind.GOOGLE_PHOTOS)
        assertThat(controller.removeUnused(id)).isTrue()
        assertThat(database.googleConnections.get(id)).isNull()
        controller.select(connection, SourceKind.GOOGLE_PHOTOS, FolderRef(id, ProviderObjectId("ambient")), "Photos", false)
        assertThat(controller.removeUnused(id)).isFalse()
        assertThat(database.googleConnections.get(id)).isEqualTo(connection)
    }

    @Test fun `v4 catalog migrates to v5 while retaining existing folders`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "google-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = Json.parseToJsonElement(File("../core/catalog/schemas/com.jedon.kellikanvas.catalog.KelliKanvasDatabase/4.json").readText()) as JsonObject
        val definition = schema["database"] as JsonObject
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            (definition["entities"] as JsonArray).forEach { item ->
                val entity = item as JsonObject
                old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content))
                (entity["indices"] as? JsonArray).orEmpty().forEach { index ->
                    old.execSQL((index as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content))
                }
            }
            (definition["setupQueries"] as JsonArray).forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO source_profiles VALUES ('existing', 'smb_v1', 'Household NAS', 'available', NULL, 123)")
            old.execSQL("INSERT INTO collections VALUES ('default', 'Family', 'not_indexed', NULL)")
            old.execSQL("INSERT INTO selected_roots VALUES ('default', 'existing', 'photos', 'Existing photos', 1)")
            old.version = 4
        }
        val migrated = KelliKanvasDatabaseFactory.create(context, name)
        try {
            assertThat(migrated.openHelper.writableDatabase.version).isEqualTo(5)
            assertThat(migrated.selectedRoots.list("default").single().displayLabel).isEqualTo("Existing photos")
            assertThat(migrated.googleConnections.list()).isEmpty()
            migrated.googleConnections.upsert(GoogleConnection(SourceProfileId("existing")))
            assertThat(migrated.googleConnections.get(SourceProfileId("existing"))).isNotNull()
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `Drive folder input accepts provider URLs and rejects other hosts`() {
        assertThat(parseDriveFolderLocation("https://drive.google.com/drive/u/0/folders/abc_123?usp=sharing")).isEqualTo("abc_123")
        assertThat(parseDriveFolderLocation("abc_123")).isEqualTo("abc_123")
        assertThat(runCatching { parseDriveFolderLocation("https://drive.google.com.evil.example/folders/private") }.isFailure).isTrue()
        assertThat(runCatching { parseDriveFolderLocation("http://drive.google.com/folders/private") }.isFailure).isTrue()
    }
}

package com.jedon.kellikanvas.account

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesRepository
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.AppTheme
import com.jedon.kellikanvas.model.LayoutMode
import com.jedon.kellikanvas.model.PlaybackOrder
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.TransitionType
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialSecret
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.connected.ConnectorStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CloudAccountTest {
    private lateinit var database: KelliKanvasDatabase
    private lateinit var account: CloudAccount
    private val server = MockWebServer()
    private val vault = TestVault()
    private val preferences = TestPreferences()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("kanvas-account", 0).edit().clear().commit()
        database = KelliKanvasDatabaseFactory.inMemory(context)
        server.start()
        account = CloudAccount(database, preferences, vault, OkHttpClient(), ConnectorStore(vault), context, server.url("/").toString())
    }

    @After fun close() {
        server.shutdown()
        database.close()
    }
    private fun json(value: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(value))
    private suspend fun link() {
        json("""{"id":"12345678-1234-1234-1234-123456789012","deviceSecret":"fixture-private-poll-secret","userCode":"ABCDEFGH","verificationUri":"${server.url("/pair?code=ABCDEFGH")}","expiresIn":600,"interval":5}""")
        val pair = account.start(server.url("/").toString(), "Fixture TV")
        assertThat(pair.uri).doesNotContain(pair.secret)
        json("""{"status":"approved","token":"fixture-account-token","deviceId":"fixture-device","user":{"id":"fixture-user","email":"fixture@example.test"}}""")
        assertThat(account.poll(pair)).isEqualTo("approved")
        server.takeRequest()
        server.takeRequest()
    }
    private fun state(revision: Int = 1, secret: String = "fixture-nas-password", provider: String = "SMB") = """{"revision":$revision,"settings":{"theme":"MIDNIGHT","slideDurationMillis":23000},"connections":[{"id":"cloud-fixture","provider":"$provider","name":"Family photographs","configuration":{"host":"darklingnas","port":445,"share":"Photos","username":"fixture","domain":"","secret":"$secret"},"roots":[{"objectId":".","name":"Family photographs","includeDescendants":true,"includedFilterIds":[]}]}]}"""

    @Test fun newlyLinkedTvRestoresAutomaticallyAndKeepsConnectionsWithNoSelectedPhotos() = runTest {
        link()
        val emptyRoots = state().replace("\"roots\":[{\"objectId\":\".\",\"name\":\"Family photographs\",\"includeDescendants\":true,\"includedFilterIds\":[]}]", "\"roots\":[]")
        json(emptyRoots)
        assertThat(account.sync()).isTrue()
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).isEmpty()
        server.takeRequest()
        json(emptyRoots)
        assertThat(account.sync()).isFalse()
        assertThat(server.takeRequest().method).isEqualTo("GET")
        server.enqueue(MockResponse().setResponseCode(204))
        account.save()
        val saved = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["connections"]!!.jsonArray
        assertThat(saved.size).isEqualTo(1)
        assertThat(saved.single().jsonObject["roots"]!!.jsonArray).isEmpty()
    }

    @Test fun `restore adds encrypted NAS login and cloud roots while retaining local photos`() = runTest {
        link()
        val local = SourceProfileId("local-fixture")
        database.sourceProfiles.upsert(SourceProfile(local, SourceKind.SAF, "USB photos", createdAtMillis = 1))
        database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Your photos"))
        database.selectedRoots.replace(SelectedRoot(CatalogIds.DEFAULT_COLLECTION_ID, local, ProviderObjectId("local-root"), "USB photos", true))
        json(state())
        account.restore()
        assertThat(preferences.preferences.value.appPreferences.theme).isEqualTo(AppTheme.MIDNIGHT)
        assertThat(preferences.preferences.value.appPreferences.slideDurationMillis).isEqualTo(23000)
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).hasSize(2)
        assertThat(vault.text(SourceProfileId("cloud-fixture"))).isEqualTo("fixture-nas-password")
        assertThat(database.smbConnections.get(SourceProfileId("cloud-fixture"))!!.share).isEqualTo("Photos")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fixture-account-token")
        server.enqueue(MockResponse().setResponseCode(204))
        account.save()
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(sent.getValue("expectedRevision").jsonPrimitive.long).isEqualTo(1)
        assertThat(sent.getValue("connections").jsonArray).hasSize(1)
        assertThat(sent.toString()).doesNotContain("local-root")
        server.enqueue(MockResponse().setResponseCode(204))
        account.disconnect()
        assertThat(account.accountLabel).isNull()
        assertThat(vault.text(SourceProfileId("cloud-fixture"))).isNull()
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID).single().profileId).isEqualTo(local)
    }

    @Test fun phoneSelectionCanReplaceLocalRootsWithoutDeletingLocalSources() = runTest {
        link()
        val local = SourceProfileId("local-fixture")
        database.sourceProfiles.upsert(SourceProfile(local, SourceKind.SAF, "USB photos", createdAtMillis = 1))
        database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Your photos"))
        database.selectedRoots.replace(SelectedRoot(CatalogIds.DEFAULT_COLLECTION_ID, local, ProviderObjectId("local-root"), "USB photos", true))
        json(state().replace("\"theme\":\"MIDNIGHT\"", "\"accountPhotosOnly\":true,\"theme\":\"MIDNIGHT\""))
        account.restore()
        server.takeRequest()
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID).single().profileId).isEqualTo(SourceProfileId("cloud-fixture"))
        assertThat(database.sourceProfiles.get(local)).isNotNull()
        server.enqueue(MockResponse().setResponseCode(204))
        account.save()
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(sent.getValue("settings").jsonObject["accountPhotosOnly"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun `sync preserves local edits when remote revision rejects a stale write`() = runTest {
        link()
        json(state())
        account.restore()
        server.takeRequest()
        preferences.update { it.copy(appPreferences = it.appPreferences.copy(theme = AppTheme.PAPER)) }
        server.enqueue(MockResponse().setResponseCode(409))
        assertThat(runCatching { account.sync() }.isFailure).isTrue()
        assertThat(preferences.preferences.value.appPreferences.theme).isEqualTo(AppTheme.PAPER)
        assertThat(account.session()!!.getValue("revision").jsonPrimitive.long).isEqualTo(1)
        assertThat(vault.text(SourceProfileId("cloud-fixture"))).isEqualTo("fixture-nas-password")
    }

    @Test fun `invalid cloud provider leaves preferences catalog and vault intact`() = runTest {
        link()
        json(state())
        account.restore()
        server.takeRequest()
        json(state(2, "changed-secret", "UNKNOWN_PROVIDER"))
        assertThat(runCatching { account.restore() }.isFailure).isTrue()
        assertThat(vault.text(SourceProfileId("cloud-fixture"))).isEqualTo("fixture-nas-password")
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).hasSize(1)
        assertThat(account.session()!!.getValue("revision").jsonPrimitive.long).isEqualTo(1)
    }

    @Test fun `default and customized settings round trip every persistent display field`() {
        val customized = AppPreferencesState(
            AppPreferences(
                theme = AppTheme.PAPER,
                landscapeLayout = LayoutMode.STRETCH,
                transitionType = TransitionType.RANDOM,
                playbackOrder = PlaybackOrder.MODIFIED_DATE_DESC,
                clockOverlayEnabled = true,
                blurDimAmount = 0.6,
                pairGutterDp = 20,
            ),
            reducedMotion = true,
        )
        assertThat(AppPreferencesState().cloudJson().cloudPreferences()).isEqualTo(AppPreferencesState())
        assertThat(customized.cloudJson().cloudPreferences()).isEqualTo(customized)
        assertThat(runCatching { validatedCloudOrigin("http://remote.example") }.isFailure).isTrue()
        assertThat(runCatching { validatedCloudOrigin("https://user:secret@remote.example") }.isFailure).isTrue()
    }
}
private class TestVault : CredentialVault {
    private val values = mutableMapOf<SourceProfileId, ByteArray>()
    override fun write(profileId: SourceProfileId, secret: ByteArray) {
        values[profileId] = secret.copyOf()
    }
    override fun write(profileId: SourceProfileId, secret: CharArray) = write(profileId, String(secret).toByteArray())
    override fun read(profileId: SourceProfileId): CredentialReadResult = values[profileId]?.let { CredentialReadResult.Present(CredentialSecret(it)) } ?: CredentialReadResult.Missing
    override fun remove(profileId: SourceProfileId) {
        values.remove(profileId)?.fill(0)
    }
    fun text(id: SourceProfileId) = values[id]?.toString(Charsets.UTF_8)
}
private class TestPreferences : AppPreferencesRepository {
    override val preferences = MutableStateFlow(AppPreferencesState())
    override suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState) {
        preferences.value = transform(preferences.value)
    }
    override suspend fun setSlideTiming(slideDurationMillis: Long, transitionDurationMillis: Long) {
        update { it.copy(appPreferences = it.appPreferences.copy(slideDurationMillis = slideDurationMillis, transitionDurationMillis = transitionDurationMillis)) }
    }
}

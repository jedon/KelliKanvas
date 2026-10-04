package com.jedon.kellikanvas.connectors

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.feature.collection.CollectionHubController
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialSecret
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.ConnectorStore
import com.jedon.kellikanvas.source.connected.PhotoConnector
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectorControllerTest {
    private lateinit var database: KelliKanvasDatabase
    private val id = SourceProfileId("fixture-source")
    private val vault = MemoryVault()
    private val store = ConnectorStore(vault)

    @Before fun setup() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
    }

    @After fun close() {
        database.close()
    }
    private fun config(secret: String = "fixture-token") = ConnectorConfiguration(PhotoConnector.IMMICH, "https://photos.example/", "", secret)
    private fun folder(objectId: String) = SourceEntry.Folder(FolderRef(id, ProviderObjectId(objectId)), "Fixture album")

    @Test fun `selection stores runtime credentials outside catalog and reconnects without duplicate roots`() = runTest {
        val controller = ConnectorController(database, store)
        controller.select(id, config(), folder("album:one"), false)
        controller.select(id, config("renewed-fixture-token"), folder("album:one"), true)
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).hasSize(1)
        assertThat(database.sourceProfiles.get(id)!!.kind).isEqualTo(SourceProfileKind.Known(PhotoConnector.IMMICH.kind))
        assertThat(store.read(id)!!.secret).isEqualTo("renewed-fixture-token")
        assertThat(database.sourceProfiles.get(id).toString()).doesNotContain("renewed-fixture-token")
        assertThat(store.read(id).toString()).doesNotContain("renewed-fixture-token")
        val restored = ConnectorStore(vault).read(id)!!
        assertThat(restored.provider).isEqualTo(PhotoConnector.IMMICH)
        assertThat(restored.endpoint.host).isEqualTo("photos.example")
        val hub = CollectionHubController(database) { store.remove(it.profileId) }
        hub.removeRoot(hub.listRoots().single())
        assertThat(store.read(id)).isNull()
        assertThat(database.sourceProfiles.get(id)).isNull()
    }

    @Test fun `failed credential storage cannot create a half connected source`() = runTest {
        vault.failWrite = true
        assertThat(runCatching { ConnectorController(database, store).select(id, config(), folder("one"), true) }.isFailure).isTrue()
        assertThat(database.sourceProfiles.get(id)).isNull()
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).isEmpty()
    }

    @Test fun `changing a saved server requires a separate source`() = runTest {
        val controller = ConnectorController(database, store)
        controller.select(id, config(), folder("one"), true)
        val different = ConnectorConfiguration(PhotoConnector.IMMICH, "https://different.example/", "", "fixture-token")
        assertThat(runCatching { controller.select(id, different, folder("two"), true) }.isFailure).isTrue()
        assertThat(store.read(id)!!.endpoint.host).isEqualTo("photos.example")
        assertThat(database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)).hasSize(1)
    }
}

private class MemoryVault : CredentialVault {
    private val secrets = mutableMapOf<SourceProfileId, ByteArray>()
    var failWrite = false
    override fun write(profileId: SourceProfileId, secret: ByteArray) {
        check(!failWrite)
        secrets[profileId] = secret.copyOf()
    }
    override fun write(profileId: SourceProfileId, secret: CharArray) = write(profileId, String(secret).toByteArray())
    override fun read(profileId: SourceProfileId): CredentialReadResult = secrets[profileId]?.let { CredentialReadResult.Present(CredentialSecret(it)) } ?: CredentialReadResult.Missing
    override fun remove(profileId: SourceProfileId) {
        secrets.remove(profileId)?.fill(0)
    }
}

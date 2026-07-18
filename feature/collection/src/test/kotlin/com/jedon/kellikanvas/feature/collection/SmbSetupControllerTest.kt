package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmbSetupControllerTest {
    private lateinit var database: KelliKanvasDatabase
    private lateinit var vault: FakeCredentialVault

    @Before
    fun setUp() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        vault = FakeCredentialVault()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun connectHousehold_persistsSmbConnectionWithoutPasswordInRoom() = runTest {
        val profileId = SourceProfileId("smb-household")
        val controller =
            SmbSetupController(
                database = database,
                credentialVault = vault,
                householdUsername = "fake-user",
                householdPassword = "fake-password".toCharArray(),
                adapterFactory = { profile, _ -> FakeSmbAdapter(profile.id) },
                profileIdFactory = { profileId },
            )

        val result = controller.connectHousehold()

        assertThat(result.host).isIn(HouseholdNasDefaults.HOST_CANDIDATES)
        assertThat(result.share).isEqualTo(HouseholdNasDefaults.PRIMARY_SHARE.share)
        assertThat(result.rootCount).isAtLeast(1)
        assertThat(result.roots).contains("Digital Photos")

        val stored = database.smbConnections.get(profileId)
        assertThat(stored).isNotNull()
        assertThat(stored!!.username).isEqualTo("fake-user")
        assertThat(stored.host).isEqualTo(result.host)
        assertThat(stored.toString()).doesNotContain("fake-password")
        assertThat(vault.stored[profileId.value]).isEqualTo("fake-password")

        val roots = database.selectedRoots.list(result.collectionId)
        assertThat(roots.map { it.objectId.value }).contains("Digital Photos")
    }

    private class FakeCredentialVault : CredentialVault {
        val stored = linkedMapOf<String, String>()

        override fun write(
            profileId: SourceProfileId,
            secret: ByteArray,
        ) {
            stored[profileId.value] = secret.toString(Charsets.UTF_8)
        }

        override fun write(
            profileId: SourceProfileId,
            secret: CharArray,
        ) {
            stored[profileId.value] = String(secret)
        }

        override fun read(profileId: SourceProfileId): CredentialReadResult = stored[profileId.value]?.let {
            CredentialReadResult.Present(
                com.jedon.kellikanvas.security.CredentialSecret(it.toByteArray(Charsets.UTF_8)),
            )
        } ?: CredentialReadResult.Missing

        override fun remove(profileId: SourceProfileId) {
            stored.remove(profileId.value)
        }
    }

    private class FakeSmbAdapter(
        override val profileId: SourceProfileId,
    ) : SourceAdapter() {
        override val kind: SourceKind = SourceKind.SMB
        override val capabilities =
            SourceCapabilities(
                supportsPaging = true,
                supportsReliableModifiedTime = true,
            )

        override suspend fun probe(): SourceStatus = SourceStatus(true, "ok")

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> {
            val path = folder.objectId.value
            val items =
                when (path) {
                    "", SmbSourceAdapter.ROOT_OBJECT_ID ->
                        HouseholdNasDefaults.PRIMARY_SHARE.photoRoots.map { root ->
                            SourceEntry.Folder(
                                ref = FolderRef(profileId, ProviderObjectId(root)),
                                name = root.substringAfterLast('/'),
                            )
                        }
                    else -> emptyList()
                }
            return Page(items = items.take(limit), nextCursor = null)
        }

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = PhotoMetadata(asset = asset)

        override suspend fun openStream(asset: AssetRef): PhotoByteStream = object : PhotoByteStream(0) {
            override suspend fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = -1

            override fun close() = Unit
        }
    }
}

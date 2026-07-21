package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.logging.BootstrapTrace
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
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbProfile
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
class HouseholdNasBootstrapConnectTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun setUp() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        BootstrapTrace.clear()
    }

    @After
    fun tearDown() {
        database.close()
        BootstrapTrace.clear()
    }

    @Test
    fun successfulSmbConnectRecordsConnectedHost() = runTest {
        val recorded = mutableListOf<String>()
        val bootstrap =
            HouseholdNasBootstrap(
                smb = smbController(resolvePreferredHost = { "192.168.68.90" }),
                dlna = unusedDlnaController(),
                hasHouseholdSmbCredentials = { true },
                recordKnownGoodIp = recorded::add,
            )

        val result = bootstrap.ensurePhotosCollection()

        assertThat(result).isInstanceOf(BootstrapResult.Success::class.java)
        assertThat(recorded).containsExactly("192.168.68.90")

        val trace = BootstrapTrace.last()
        assertThat(trace).isNotNull()
        assertThat(trace!!.result).startsWith("Success:")
        assertThat(trace.steps.map { it.name }).contains("SMB credentials")
        assertThat(trace.steps.any { it.name.startsWith("SMB connect+auth") && it.ok }).isTrue()
        assertThat(trace.steps.any { it.name.startsWith("SMB root listing") && it.ok }).isTrue()
    }

    @Test
    fun failedBootstrapRecordsNothing() = runTest {
        val recorded = mutableListOf<String>()
        val bootstrap =
            HouseholdNasBootstrap(
                smb = smbController(
                    resolvePreferredHost = { null },
                    adapterFactory = { _, _ -> error("SMB unreachable") },
                ),
                dlna = unusedDlnaController(),
                hasHouseholdSmbCredentials = { true },
                recordKnownGoodIp = recorded::add,
            )

        val result = bootstrap.ensurePhotosCollection()

        assertThat(result).isInstanceOf(BootstrapResult.Failed::class.java)
        assertThat(recorded).isEmpty()

        val trace = BootstrapTrace.last()
        assertThat(trace).isNotNull()
        assertThat(trace!!.result).startsWith("Failed:")
        val failedConnects = trace.steps.filter { it.name.startsWith("SMB connect") && !it.ok }
        assertThat(failedConnects).isNotEmpty()
        assertThat(failedConnects.first().detail).contains("SMB unreachable")
    }

    private fun smbController(
        resolvePreferredHost: suspend () -> String?,
        adapterFactory: (SmbProfile, SmbCredentials) -> SourceAdapter = { profile, _ ->
            FakeSmbAdapter(profile.id)
        },
    ): SmbSetupController = SmbSetupController(
        database = database,
        credentialVault = NoopCredentialVault(),
        householdUsername = "fake-user",
        householdPassword = "fake-password".toCharArray(),
        adapterFactory = adapterFactory,
        resolvePreferredHost = resolvePreferredHost,
    )

    private fun unusedDlnaController(): DlnaSetupController = DlnaSetupController(
        database = database,
        discoverProfiles = { error("DLNA must not be used") },
        resolveManual = { error("DLNA must not be used") },
        resolveBuiltIn = { error("DLNA must not be used") },
        adapterFactory = { error("DLNA must not be used") },
    )

    private class NoopCredentialVault : CredentialVault {
        override fun write(
            profileId: SourceProfileId,
            secret: ByteArray,
        ) = Unit

        override fun write(
            profileId: SourceProfileId,
            secret: CharArray,
        ) = Unit

        override fun read(profileId: SourceProfileId): CredentialReadResult = CredentialReadResult.Missing

        override fun remove(profileId: SourceProfileId) = Unit
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
            val items =
                when (folder.objectId.value) {
                    "", SmbSourceAdapter.ROOT_OBJECT_ID ->
                        listOf(
                            SourceEntry.Folder(
                                ref = FolderRef(
                                    profileId,
                                    ProviderObjectId("Frame TV landscape photos_mix"),
                                ),
                                name = "Frame TV landscape photos_mix",
                            ),
                        )
                    "Frame TV landscape photos_mix" ->
                        listOf(
                            SourceEntry.Folder(
                                ref = FolderRef(
                                    profileId,
                                    ProviderObjectId(HouseholdNasDefaults.FRAME_TV_16X9_PATH),
                                ),
                                name = "16X9",
                            ),
                        )
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

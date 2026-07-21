package com.jedon.kellikanvas.diagnostics

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.SelectedRoot
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
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConnectivityChecksTest {
    private val smbProfileId = SourceProfileId("smb-1")
    private val root = SelectedRoot(
        collectionId = "collection",
        profileId = smbProfileId,
        objectId = ProviderObjectId("Photos/16X9"),
        displayLabel = "16X9",
        includeDescendants = true,
    )

    private fun checks(
        tcpReachable: Boolean = true,
        smbAdapter: SourceAdapter? = null,
        dlnaNames: List<String> = emptyList(),
        roots: List<SelectedRoot> = emptyList(),
        adapters: Map<SourceProfileId, SourceAdapter> = emptyMap(),
    ): List<ConnectivityCheck> = buildConnectivityChecks(
        nasHost = "192.168.68.90",
        tcpProbe = { _, _ -> tcpReachable },
        smbAdapter = smbAdapter,
        smbUnavailableReason = "no SMB source configured",
        discoverDlnaServerNames = { dlnaNames },
        roots = roots,
        adapters = adapters,
    )

    @Test
    fun `check list covers tcp ports smb dlna and each configured root`() {
        val names = checks(roots = listOf(root)).map(ConnectivityCheck::name)

        assertThat(names).containsExactly(
            "TCP 192.168.68.90:445",
            "TCP 192.168.68.90:8088",
            "SMB auth + share listing",
            "DLNA SSDP discovery",
            "Root \"16X9\"",
        ).inOrder()
    }

    @Test
    fun `tcp check maps probe result to outcome`() = runTest {
        val reachable = checks(tcpReachable = true).first().run()
        assertThat(reachable.ok).isTrue()

        val unreachable = checks(tcpReachable = false).first().run()
        assertThat(unreachable.ok).isFalse()
        assertThat(unreachable.detail).contains("no connection")
    }

    @Test
    fun `smb check fails with reason when no adapter is available`() = runTest {
        val outcome = checks().first { it.name.startsWith("SMB") }.run()

        assertThat(outcome.ok).isFalse()
        assertThat(outcome.detail).isEqualTo("no SMB source configured")
    }

    @Test
    fun `smb check reports probe summary when auth fails`() = runTest {
        val adapter = FakeAdapter(
            profileId = smbProfileId,
            kind = SourceKind.SMB,
            status = SourceStatus(available = false, summary = "Access denied"),
        )

        val outcome = checks(smbAdapter = adapter).first { it.name.startsWith("SMB") }.run()

        assertThat(outcome.ok).isFalse()
        assertThat(outcome.detail).isEqualTo("Access denied")
    }

    @Test
    fun `smb check lists the share root when auth succeeds`() = runTest {
        val adapter = FakeAdapter(
            profileId = smbProfileId,
            kind = SourceKind.SMB,
            status = SourceStatus(available = true, summary = "OK"),
            entries = listOf(folderEntry("Photos"), folderEntry("Kelli")),
        )

        val outcome = checks(smbAdapter = adapter).first { it.name.startsWith("SMB") }.run()

        assertThat(outcome.ok).isTrue()
        assertThat(outcome.detail).isEqualTo("auth OK; share root has 2 entries")
    }

    @Test
    fun `dlna check passes only when a server responds`() = runTest {
        val silent = checks().first { it.name.startsWith("DLNA") }.run()
        assertThat(silent.ok).isFalse()

        val found = checks(dlnaNames = listOf("DarklingNAS")).first { it.name.startsWith("DLNA") }.run()
        assertThat(found.ok).isTrue()
        assertThat(found.detail).isEqualTo("DarklingNAS")
    }

    @Test
    fun `root check samples listChildren or fails when adapter missing`() = runTest {
        val adapter = FakeAdapter(
            profileId = smbProfileId,
            kind = SourceKind.SMB,
            status = SourceStatus(available = true, summary = "OK"),
            entries = listOf(folderEntry("A"), folderEntry("B"), folderEntry("C")),
        )

        val sampled = checks(roots = listOf(root), adapters = mapOf(smbProfileId to adapter))
            .first { it.name.startsWith("Root") }
            .run()
        assertThat(sampled.ok).isTrue()
        assertThat(sampled.detail).isEqualTo("sampled 3 entries")

        val missing = checks(roots = listOf(root)).first { it.name.startsWith("Root") }.run()
        assertThat(missing.ok).isFalse()
        assertThat(missing.detail).isEqualTo("adapter not restored")
    }

    private fun folderEntry(name: String): SourceEntry = SourceEntry.Folder(
        ref = FolderRef(smbProfileId, ProviderObjectId(name)),
        name = name,
    )

    private class FakeAdapter(
        override val profileId: SourceProfileId,
        override val kind: SourceKind,
        private val status: SourceStatus,
        private val entries: List<SourceEntry> = emptyList(),
    ) : SourceAdapter() {
        override val capabilities: SourceCapabilities = SourceCapabilities()

        override suspend fun probe(): SourceStatus = status

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> = Page(entries)

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = error("unused in connectivity checks")

        override suspend fun openStream(asset: AssetRef): PhotoByteStream = error("unused in connectivity checks")
    }
}

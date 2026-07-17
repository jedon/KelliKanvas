package com.jedon.kellikanvas.source

import com.google.common.truth.Truth.assertThat
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class SourceAdapterTest {
    private val profileId = SourceProfileId("profile")
    private val folder = FolderRef(profileId, ProviderObjectId("folder"))

    @Test
    fun `adapter exposes identity capabilities and core source operations`() = runTest {
        val adapter = RecordingAdapter()

        assertThat(adapter.profileId).isEqualTo(profileId)
        assertThat(adapter.kind).isEqualTo(SourceKind.SMB)
        assertThat(adapter.capabilities.supportsPaging).isTrue()
        assertThat(adapter.probe().available).isTrue()
        assertThat(adapter.listChildren(folder, null).items).isEmpty()
        assertThat(adapter.recordedLimit).isEqualTo(100)
    }

    @Test
    fun `adapter rejects page limits outside one through five hundred`() = runTest {
        val adapter = RecordingAdapter()

        assertInvalidLimit(adapter, 0)
        assertInvalidLimit(adapter, 501)
        assertThat(adapter.listChildren(folder, PageCursor("next"), 1).items).isEmpty()
        assertThat(adapter.listChildren(folder, null, 500).items).isEmpty()
    }

    private suspend fun assertInvalidLimit(
        adapter: SourceAdapter,
        limit: Int,
    ) {
        try {
            adapter.listChildren(folder, null, limit)
            fail("Expected limit $limit to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected contract violation.
        }
    }

    private inner class RecordingAdapter : SourceAdapter {
        override val profileId = this@SourceAdapterTest.profileId
        override val kind = SourceKind.SMB
        override val capabilities = SourceCapabilities(supportsPaging = true)
        var recordedLimit: Int? = null

        override suspend fun probe() = SourceStatus(available = true, summary = "Connected")

        override suspend fun listChildren(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> {
            validatePageLimit(limit)
            recordedLimit = limit
            return Page(emptyList())
        }

        override suspend fun metadata(asset: AssetRef): PhotoMetadata = PhotoMetadata(asset)

        override suspend fun open(asset: AssetRef): PhotoByteStream = error("Not needed by this test")
    }
}

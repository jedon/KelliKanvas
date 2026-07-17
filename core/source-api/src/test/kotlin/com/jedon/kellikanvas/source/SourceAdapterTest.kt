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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class SourceAdapterTest {
    private val profileId = SourceProfileId("profile")
    private val folder = FolderRef(profileId, ProviderObjectId("folder"))
    private val asset =
        AssetRef(
            profileId = profileId,
            objectId = ProviderObjectId("asset"),
            mimeType = "image/jpeg",
        )

    @Test
    fun `adapter exposes identity capabilities and core source operations`() = runTest {
        val adapter = RecordingAdapter()

        assertThat(adapter.profileId).isEqualTo(profileId)
        assertThat(adapter.kind).isEqualTo(SourceKind.SMB)
        assertThat(adapter.capabilities.supportsPaging).isTrue()
        assertThat(adapter.probe().available).isTrue()
        assertThat(adapter.listChildren(folder, null).items).isEmpty()
        assertThat(adapter.recordedLimit).isEqualTo(100)
        assertThat(adapter.metadata(asset).asset).isEqualTo(asset)
        adapter.open(asset).close()
        assertThat(adapter.listChildrenCalls).isEqualTo(1)
        assertThat(adapter.metadataCalls).isEqualTo(1)
        assertThat(adapter.openCalls).isEqualTo(1)
    }

    @Test
    fun `adapter rejects page limits outside one through five hundred`() = runTest {
        val adapter = RecordingAdapter()

        assertInvalidLimit(adapter, 0)
        assertThat(adapter.recordedLimit).isNull()
        assertInvalidLimit(adapter, 501)
        assertThat(adapter.recordedLimit).isNull()
        assertThat(adapter.listChildren(folder, PageCursor("next"), 1).items).isEmpty()
        assertThat(adapter.listChildren(folder, null, 500).items).isEmpty()
    }

    @Test
    fun `adapter rejects cross-profile references before delegates run`() = runTest {
        val otherProfile = SourceProfileId("other-profile")
        val otherFolder = FolderRef(otherProfile, ProviderObjectId("folder"))
        val otherAsset =
            AssetRef(
                profileId = otherProfile,
                objectId = ProviderObjectId("asset"),
                mimeType = "image/jpeg",
            )
        val adapter = RecordingAdapter()

        assertProfileMismatch { adapter.listChildren(otherFolder, null) }
        assertProfileMismatch { adapter.metadata(otherAsset) }
        assertProfileMismatch { adapter.open(otherAsset) }

        assertThat(adapter.listChildrenCalls).isEqualTo(0)
        assertThat(adapter.metadataCalls).isEqualTo(0)
        assertThat(adapter.openCalls).isEqualTo(0)
    }

    @Test
    fun `adapter propagates cancellation from page loading unchanged`() = runTest {
        val cancellation = CancellationException("cancelled")
        val adapter = RecordingAdapter(pageFailure = cancellation)

        try {
            adapter.listChildren(folder, null)
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertThat(caught).isSameInstanceAs(cancellation)
        }
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

    private suspend fun assertProfileMismatch(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cross-profile reference to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected contract violation.
        }
    }

    private inner class RecordingAdapter(
        private val pageFailure: Throwable? = null,
    ) : SourceAdapter() {
        override val profileId = this@SourceAdapterTest.profileId
        override val kind = SourceKind.SMB
        override val capabilities = SourceCapabilities(supportsPaging = true)
        var recordedLimit: Int? = null
        var listChildrenCalls = 0
        var metadataCalls = 0
        var openCalls = 0

        override suspend fun probe() = SourceStatus(available = true, summary = "Connected")

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> {
            listChildrenCalls += 1
            pageFailure?.let { throw it }
            recordedLimit = limit
            return Page(emptyList())
        }

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata {
            metadataCalls += 1
            return PhotoMetadata(asset)
        }

        override suspend fun openStream(asset: AssetRef): PhotoByteStream {
            openCalls += 1
            return EmptyPhotoByteStream()
        }
    }

    private class EmptyPhotoByteStream : PhotoByteStream(contentLength = 0) {
        override suspend fun readAtMostTo(
            sink: okio.Buffer,
            byteCount: Long,
        ): Long = -1

        override fun close() = Unit
    }
}

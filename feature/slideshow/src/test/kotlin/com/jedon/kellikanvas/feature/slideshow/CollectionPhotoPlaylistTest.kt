package com.jedon.kellikanvas.feature.slideshow

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class CollectionPhotoPlaylistTest {

    private val profileId1 = SourceProfileId("profile-1")
    private val profileId2 = SourceProfileId("profile-2")

    private class FakeSourceAdapter(
        override val profileId: SourceProfileId,
        private val childrenMap: Map<FolderRef, List<SourceEntry>>,
    ) : SourceAdapter() {
        override val kind = SourceKind.SAF
        override val capabilities = SourceCapabilities(supportsPaging = true)

        override suspend fun probe(): SourceStatus = SourceStatus(available = true, summary = "Fake active")

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> {
            val allChildren = childrenMap[folder] ?: emptyList()
            val startIndex = cursor?.value?.toIntOrNull() ?: 0
            if (startIndex >= allChildren.size) {
                return Page(emptyList(), null)
            }
            val endIndex = (startIndex + limit).coerceAtMost(allChildren.size)
            val items = allChildren.subList(startIndex, endIndex)
            val nextCursor = if (endIndex < allChildren.size) PageCursor(endIndex.toString()) else null
            return Page(items, nextCursor)
        }

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = PhotoMetadata(asset)

        override suspend fun openStream(asset: AssetRef): PhotoByteStream = throw UnsupportedOperationException()
    }

    private class CancellingSourceAdapter(
        override val profileId: SourceProfileId,
        private val cancellation: CancellationException = CancellationException("listing cancelled"),
    ) : SourceAdapter() {
        override val kind = SourceKind.SAF
        override val capabilities = SourceCapabilities(supportsPaging = true)

        override suspend fun probe(): SourceStatus = SourceStatus(available = true, summary = "Fake cancelling")

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> = throw cancellation

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = PhotoMetadata(asset)

        override suspend fun openStream(asset: AssetRef): PhotoByteStream = throw UnsupportedOperationException()
    }

    private class FailingSourceAdapter(
        override val profileId: SourceProfileId,
    ) : SourceAdapter() {
        override val kind = SourceKind.SAF
        override val capabilities = SourceCapabilities(supportsPaging = true)

        override suspend fun probe(): SourceStatus = SourceStatus(available = true, summary = "Fake failing")

        override suspend fun listChildrenPage(
            folder: FolderRef,
            cursor: PageCursor?,
            limit: Int,
        ): Page<SourceEntry> = throw RuntimeException("Listing failed for ${folder.objectId.value}")

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = PhotoMetadata(asset)

        override suspend fun openStream(asset: AssetRef): PhotoByteStream = throw UnsupportedOperationException()
    }

    private fun createPhoto(profileId: SourceProfileId, id: String): SourceEntry.Photo = SourceEntry.Photo(
        asset = AssetRef(profileId, ProviderObjectId(id), "image/jpeg"),
        name = id,
    )

    @Test
    fun build_multipleAdapters_mergesPhotosFromAllRoots() = runTest {
        val root1Ref = FolderRef(profileId1, ProviderObjectId("root1"))
        val root2Ref = FolderRef(profileId2, ProviderObjectId("root2"))

        val adapter1 = FakeSourceAdapter(
            profileId1,
            mapOf(root1Ref to listOf(createPhoto(profileId1, "photo1"))),
        )
        val adapter2 = FakeSourceAdapter(
            profileId2,
            mapOf(root2Ref to listOf(createPhoto(profileId2, "photo2"))),
        )

        val roots = listOf(
            SelectedRoot("col1", profileId1, ProviderObjectId("root1"), "Root 1", includeDescendants = false),
            SelectedRoot("col1", profileId2, ProviderObjectId("root2"), "Root 2", includeDescendants = false),
        )

        val result = CollectionPhotoPlaylist.build(
            adapters = mapOf(profileId1 to adapter1, profileId2 to adapter2),
            roots = roots,
        )

        assertThat(result).hasSize(2)
        assertThat(result[0].objectId.value).isEqualTo("photo1")
        assertThat(result[0].profileId).isEqualTo(profileId1)
        assertThat(result[1].objectId.value).isEqualTo("photo2")
        assertThat(result[1].profileId).isEqualTo(profileId2)
    }

    @Test
    fun build_rootListingCancelled_propagatesCancellation() = runTest {
        val rootRef = FolderRef(profileId1, ProviderObjectId("root"))
        val cancellation = CancellationException("listing cancelled")
        val adapter = CancellingSourceAdapter(profileId1, cancellation)
        val roots = listOf(
            SelectedRoot("col1", profileId1, rootRef.objectId, "Root", includeDescendants = false),
        )

        try {
            CollectionPhotoPlaylist.build(
                adapters = mapOf(profileId1 to adapter),
                roots = roots,
            )
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertThat(caught).isSameInstanceAs(cancellation)
        }
    }

    @Test
    fun build_rootListingFails_skipsRootAndContinues() = runTest {
        val failingRootRef = FolderRef(profileId1, ProviderObjectId("bad-root"))
        val goodRootRef = FolderRef(profileId2, ProviderObjectId("good-root"))

        val failingAdapter = FailingSourceAdapter(profileId1)
        val goodAdapter = FakeSourceAdapter(
            profileId2,
            mapOf(goodRootRef to listOf(createPhoto(profileId2, "photo2"))),
        )

        val roots = listOf(
            SelectedRoot("col1", profileId1, failingRootRef.objectId, "Bad Root", includeDescendants = false),
            SelectedRoot("col1", profileId2, goodRootRef.objectId, "Good Root", includeDescendants = false),
        )

        val result = CollectionPhotoPlaylist.build(
            adapters = mapOf(profileId1 to failingAdapter, profileId2 to goodAdapter),
            roots = roots,
        )

        assertThat(result).hasSize(1)
        assertThat(result[0].objectId.value).isEqualTo("photo2")
    }
}

package com.jedon.kellikanvas.home

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
import org.junit.Test

class GalleryPreviewTest {
    private val profile = SourceProfileId("gallery")
    private fun root(id: String = "root", recursive: Boolean = true, filters: Set<String> = emptySet()) = SelectedRoot("default", profile, ProviderObjectId(id), id, recursive, filters)
    private fun photo(id: String = "photo", mime: String = "image/jpeg") = SourceEntry.Photo(AssetRef(profile, ProviderObjectId(id), mime), id)
    private fun folder(id: String) = SourceEntry.Folder(FolderRef(profile, ProviderObjectId(id)), id)

    @Test
    fun previewDoesNotDescendWhenSubfoldersAreExcluded() = runTest {
        val source = PreviewSource { _, _ -> Page(listOf(folder("child"))) }
        assertThat(findGalleryPreviewAssets(mapOf(profile to source), listOf(root(recursive = false)))).isEmpty()
        assertThat(source.calls).isEqualTo(1)
    }

    @Test
    fun previewEnforcesRequestBudgetOnEndlessPaging() = runTest {
        val source = PreviewSource { _, _ -> Page(emptyList(), PageCursor("page-$calls")) }
        assertThat(findGalleryPreviewAssets(mapOf(profile to source), listOf(root()))).isEmpty()
        assertThat(source.calls).isEqualTo(12)
    }

    @Test
    fun previewStopsWhenServerRepeatsItsCursor() = runTest {
        val source = PreviewSource { _, _ -> Page(emptyList(), PageCursor("same")) }
        findGalleryPreviewAssets(mapOf(profile to source), listOf(root()))
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun rootsOnSameSourceRetainTheirOwnFileFilters() = runTest {
        val source = PreviewSource { _, _ -> Page(listOf(photo("jpeg"), photo("png", "image/png"))) }
        val result = findGalleryPreviewAssets(
            mapOf(profile to source),
            listOf(
                root("one", filters = setOf("image/jpeg")),
                root("two", filters = setOf("image/png")),
            ),
        )
        assertThat(result.map { it.mimeType }).containsExactly("image/jpeg", "image/png").inOrder()
    }

    @Test
    fun inaccessibleFolderDoesNotPreventPreviewFromAnotherRoot() = runTest {
        val source = PreviewSource { folder, _ ->
            if (folder.objectId.value == "broken") error("Unavailable")
            Page(listOf(photo()))
        }
        val result = findGalleryPreviewAssets(mapOf(profile to source), listOf(root("broken"), root("working")))
        assertThat(result).hasSize(1)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagatesRatherThanContinuingDiscovery() = runTest {
        val source = PreviewSource { _, _ -> throw CancellationException("Navigation cancelled") }
        findGalleryPreviewAssets(mapOf(profile to source), listOf(root()))
    }

    private inner class PreviewSource(
        val listing: PreviewSource.(FolderRef, PageCursor?) -> Page<SourceEntry>,
    ) : SourceAdapter() {
        var calls = 0
        override val profileId = profile
        override val kind = SourceKind.SMB
        override val capabilities = SourceCapabilities(supportsPaging = true)
        override suspend fun probe() = SourceStatus(true, "Available")
        override suspend fun listChildrenPage(folder: FolderRef, cursor: PageCursor?, limit: Int): Page<SourceEntry> {
            calls++
            return listing(folder, cursor)
        }
        override suspend fun metadataFor(asset: AssetRef) = PhotoMetadata(asset)
        override suspend fun openStream(asset: AssetRef): PhotoByteStream = error("Preview discovery must not open images")
    }
}

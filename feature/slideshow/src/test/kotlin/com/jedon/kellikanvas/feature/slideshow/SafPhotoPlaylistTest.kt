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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SafPhotoPlaylistTest {

    private val profileId = SourceProfileId("profile-1")

    private class FakeSourceAdapter(
        override val profileId: SourceProfileId,
        private val childrenMap: Map<FolderRef, List<SourceEntry>>,
    ) : SourceAdapter() {
        override val kind = SourceKind.SAF
        override val capabilities = SourceCapabilities(supportsPaging = true)

        override suspend fun probe(): SourceStatus {
            return SourceStatus(available = true, summary = "Fake active")
        }

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

        override suspend fun metadataFor(asset: AssetRef): PhotoMetadata {
            return PhotoMetadata(asset)
        }

        override suspend fun openStream(asset: AssetRef): PhotoByteStream {
            throw UnsupportedOperationException()
        }
    }

    private fun createPhoto(id: String, mimeType: String = "image/jpeg"): SourceEntry.Photo {
        return SourceEntry.Photo(
            asset = AssetRef(profileId, ProviderObjectId(id), mimeType),
            name = id
        )
    }

    private fun createFolder(id: String): SourceEntry.Folder {
        return SourceEntry.Folder(
            ref = FolderRef(profileId, ProviderObjectId(id)),
            name = id
        )
    }

    @Test
    fun build_withoutDescendants_onlyDirectPhotos() = runTest {
        val root1Ref = FolderRef(profileId, ProviderObjectId("root1"))
        val root2Ref = FolderRef(profileId, ProviderObjectId("root2"))
        val folder1Ref = FolderRef(profileId, ProviderObjectId("folder1"))

        val photo1 = createPhoto("photo1")
        val folder1 = createFolder("folder1")
        val photo2 = createPhoto("photo2")
        val photo3 = createPhoto("photo3")

        val childrenMap = mapOf(
            root1Ref to listOf(photo1, folder1),
            root2Ref to listOf(photo2),
            folder1Ref to listOf(photo3)
        )

        val adapter = FakeSourceAdapter(profileId, childrenMap)
        val roots = listOf(
            SelectedRoot("col1", profileId, ProviderObjectId("root1"), "Root 1", includeDescendants = false),
            SelectedRoot("col1", profileId, ProviderObjectId("root2"), "Root 2", includeDescendants = false)
        )

        val result = SafPhotoPlaylist.build(adapter, roots)

        assertThat(result).hasSize(2)
        assertThat(result[0].objectId.value).isEqualTo("photo1")
        assertThat(result[1].objectId.value).isEqualTo("photo2")
    }

    @Test
    fun build_withDescendants_recursivePhotos() = runTest {
        val root1Ref = FolderRef(profileId, ProviderObjectId("root1"))
        val folder1Ref = FolderRef(profileId, ProviderObjectId("folder1"))
        val folder2Ref = FolderRef(profileId, ProviderObjectId("folder2"))

        val photo1 = createPhoto("photo1")
        val folder1 = createFolder("folder1")
        val photo2 = createPhoto("photo2")
        val folder2 = createFolder("folder2")
        val photo3 = createPhoto("photo3")
        val photo4 = createPhoto("photo4")

        val childrenMap = mapOf(
            root1Ref to listOf(photo1, folder1, photo4),
            folder1Ref to listOf(photo2, folder2),
            folder2Ref to listOf(photo3)
        )

        val adapter = FakeSourceAdapter(profileId, childrenMap)
        val roots = listOf(
            SelectedRoot("col1", profileId, ProviderObjectId("root1"), "Root 1", includeDescendants = true)
        )

        val result = SafPhotoPlaylist.build(adapter, roots)

        assertThat(result).hasSize(4)
        assertThat(result.map { it.objectId.value }).containsExactly("photo1", "photo2", "photo3", "photo4").inOrder()
    }

    @Test
    fun build_withFilters_filtersCorrectly() = runTest {
        val rootRef = FolderRef(profileId, ProviderObjectId("root"))

        val photoJpeg = createPhoto("photoJpeg", "image/jpeg")
        val photoPng = createPhoto("photoPng", "image/png")
        val photoGif = createPhoto("photoGif", "image/gif")

        val childrenMap = mapOf(
            rootRef to listOf(photoJpeg, photoPng, photoGif)
        )

        val adapter = FakeSourceAdapter(profileId, childrenMap)
        val roots = listOf(
            SelectedRoot(
                collectionId = "col1",
                profileId = profileId,
                objectId = ProviderObjectId("root"),
                displayLabel = "Root",
                includeDescendants = false,
                fileTypeFilters = setOf("image/jpeg", "image/png")
            )
        )

        val result = SafPhotoPlaylist.build(adapter, roots)

        assertThat(result.map { it.objectId.value }).containsExactly("photoJpeg", "photoPng").inOrder()
    }

    @Test
    fun build_exceedsCap_throwsIllegalStateException() = runTest {
        val rootRef = FolderRef(profileId, ProviderObjectId("root"))
        // Create 5001 photos
        val list = List(5001) { createPhoto("photo$it") }
        val childrenMap = mapOf(rootRef to list)

        val adapter = FakeSourceAdapter(profileId, childrenMap)
        val roots = listOf(
            SelectedRoot("col1", profileId, ProviderObjectId("root"), "Root", includeDescendants = false)
        )

        var exception: IllegalStateException? = null
        try {
            SafPhotoPlaylist.build(adapter, roots)
        } catch (e: IllegalStateException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.message).contains("Playlist size limit exceeded")
    }
}

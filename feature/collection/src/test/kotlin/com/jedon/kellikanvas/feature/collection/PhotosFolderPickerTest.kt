package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotosFolderPickerTest {
    @Test
    fun findsPhotosCaseInsensitive() {
        val folders = listOf(
            BrowseEntry("1", "Music", true),
            BrowseEntry("2", "Videos", true),
            BrowseEntry("3", "photos", true),
        )
        assertThat(PhotosFolderPicker.findPhotosFolder(folders)?.objectId).isEqualTo("3")
    }

    @Test
    fun selectedFrameTv16x9UnderPhotos() = runTest {
        val tree =
            mapOf(
                "0" to listOf(
                    BrowseEntry("music", "Music", true),
                    BrowseEntry("photos", "Photos", true),
                ),
                "photos" to listOf(
                    BrowseEntry("mix", "Frame TV landscape photos_mix", true),
                    BrowseEntry("digital", "Digital Photos", true),
                ),
                "mix" to listOf(
                    BrowseEntry("sixteen", "16X9", true),
                    BrowseEntry("four", "4X3", true),
                ),
            )
        val selected =
            PhotosFolderPicker.selectedFrameTv16x9Folder { id ->
                tree[id].orEmpty()
            }
        assertThat(selected).isEqualTo(
            SelectedFolder(
                objectId = "sixteen",
                label = "Frame TV landscape photos_mix/16X9",
                includeDescendants = true,
            ),
        )
    }

    @Test
    fun selectedFrameTv16x9MissingReturnsNull() = runTest {
        val tree =
            mapOf(
                "0" to listOf(BrowseEntry("photos", "Photos", true)),
                "photos" to listOf(BrowseEntry("digital", "Digital Photos", true)),
            )
        assertThat(
            PhotosFolderPicker.selectedFrameTv16x9Folder { id -> tree[id].orEmpty() },
        ).isNull()
    }

    @Test
    fun doesNotSelectWholePhotosFolder() = runTest {
        val tree =
            mapOf(
                "0" to listOf(BrowseEntry("photos", "Photos", true)),
                "photos" to emptyList(),
            )
        assertThat(
            PhotosFolderPicker.selectedFrameTv16x9Folder { id -> tree[id].orEmpty() },
        ).isNull()
    }

    @Test
    fun ignoresNonFoldersNamedPhotos() {
        val folders = listOf(
            BrowseEntry("1", "Photos", isFolder = false),
            BrowseEntry("2", "Other", true),
        )
        assertThat(PhotosFolderPicker.findPhotosFolder(folders)).isNull()
    }
}

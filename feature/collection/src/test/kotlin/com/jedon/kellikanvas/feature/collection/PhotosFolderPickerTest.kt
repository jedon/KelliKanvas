package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
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
    fun selectedPhotosIncludesDescendants() {
        val folders = listOf(
            BrowseEntry("1", "Music", true),
            BrowseEntry("9", "Photos", true),
        )
        val selected = PhotosFolderPicker.selectedPhotosFolder(folders)
        assertThat(selected).isEqualTo(
            SelectedFolder(objectId = "9", label = "Photos", includeDescendants = true),
        )
    }

    @Test
    fun missingPhotosReturnsNull() {
        val folders = listOf(
            BrowseEntry("1", "Music", true),
            BrowseEntry("2", "Videos", true),
        )
        assertThat(PhotosFolderPicker.findPhotosFolder(folders)).isNull()
        assertThat(PhotosFolderPicker.selectedPhotosFolder(folders)).isNull()
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

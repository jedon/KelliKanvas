package com.jedon.kellikanvas.feature.collection

/**
 * Picks the standard QNAP DLNA top-level Photos folder from a browse listing.
 */
object PhotosFolderPicker {
    const val PHOTOS_TITLE: String = "Photos"

    fun findPhotosFolder(folders: List<BrowseEntry>): BrowseEntry? =
        folders.firstOrNull { entry ->
            entry.isFolder && entry.title.equals(PHOTOS_TITLE, ignoreCase = true)
        }

    fun selectedPhotosFolder(folders: List<BrowseEntry>): SelectedFolder? {
        val photos = findPhotosFolder(folders) ?: return null
        return SelectedFolder(
            objectId = photos.objectId,
            label = photos.title,
            includeDescendants = true,
        )
    }
}

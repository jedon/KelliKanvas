package com.jedon.kellikanvas.feature.collection

/**
 * Picks the Frame TV 16×9 landscape folder from a QNAP DLNA browse tree.
 * Prefers Photos → Frame TV landscape photos_mix → 16X9; never selects whole Photos.
 */
object PhotosFolderPicker {
    const val PHOTOS_TITLE: String = "Photos"
    const val FRAME_TV_MIX_TITLE: String = "Frame TV landscape photos_mix"
    const val FRAME_TV_16X9_TITLE: String = "16X9"

    fun findPhotosFolder(folders: List<BrowseEntry>): BrowseEntry? = folders.firstOrNull { entry ->
        entry.isFolder && entry.title.equals(PHOTOS_TITLE, ignoreCase = true)
    }

    fun findNamedFolder(
        folders: List<BrowseEntry>,
        title: String,
    ): BrowseEntry? = folders.firstOrNull { entry ->
        entry.isFolder && entry.title.equals(title, ignoreCase = true)
    }

    /**
     * Walks DLNA folders to the Frame TV 16×9 leaf.
     * [listChildren] receives a ContentDirectory object id (use `"0"` for root).
     */
    suspend fun selectedFrameTv16x9Folder(
        listChildren: suspend (folderObjectId: String) -> List<BrowseEntry>,
    ): SelectedFolder? {
        val root = listChildren("0")
        val photos = findPhotosFolder(root)
        val searchRoots =
            buildList {
                if (photos != null) add(photos.objectId)
                add("0")
            }.distinct()

        var mix: BrowseEntry? = null
        for (parentId in searchRoots) {
            val folders = if (parentId == "0") root else listChildren(parentId)
            mix = findNamedFolder(folders, FRAME_TV_MIX_TITLE)
            if (mix != null) break
        }
        val mixFolder = mix ?: return null
        val mixChildren = listChildren(mixFolder.objectId)
        val sixteenByNine = findNamedFolder(mixChildren, FRAME_TV_16X9_TITLE) ?: return null
        return SelectedFolder(
            objectId = sixteenByNine.objectId,
            label = "${mixFolder.title}/${sixteenByNine.title}",
            includeDescendants = true,
        )
    }
}

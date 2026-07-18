package com.jedon.kellikanvas.feature.slideshow

import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.SourceAdapter

object CollectionPhotoPlaylist {
    private const val MAX_PHOTOS_LIMIT = 5000

    suspend fun build(
        adapters: Map<SourceProfileId, SourceAdapter>,
        roots: List<SelectedRoot>,
        pageSize: Int = 64,
    ): List<AssetRef> {
        val results = mutableListOf<AssetRef>()

        for (root in roots) {
            val adapter = adapters[root.profileId] ?: continue
            runCatching {
                val rootFolder = FolderRef(root.profileId, root.objectId)
                collectPhotos(
                    adapter = adapter,
                    folder = rootFolder,
                    includeDescendants = root.includeDescendants,
                    fileTypeFilters = root.fileTypeFilters,
                    pageSize = pageSize,
                    results = results,
                )
            }.onFailure { throwable ->
                if (throwable is IllegalStateException &&
                    throwable.message?.contains("Playlist size limit exceeded") == true
                ) {
                    throw throwable
                }
            }
        }

        return results
    }

    private suspend fun collectPhotos(
        adapter: SourceAdapter,
        folder: FolderRef,
        includeDescendants: Boolean,
        fileTypeFilters: Set<String>,
        pageSize: Int,
        results: MutableList<AssetRef>,
    ) {
        var cursor: PageCursor? = null
        do {
            val page = adapter.listChildren(folder, cursor, pageSize)
            for (entry in page.items) {
                when (entry) {
                    is SourceEntry.Photo -> {
                        if (fileTypeFilters.isEmpty() || fileTypeFilters.contains(entry.asset.mimeType)) {
                            results.add(entry.asset)
                            if (results.size > MAX_PHOTOS_LIMIT) {
                                throw IllegalStateException("Playlist size limit exceeded")
                            }
                        }
                    }
                    is SourceEntry.Folder -> {
                        if (includeDescendants) {
                            collectPhotos(
                                adapter = adapter,
                                folder = entry.ref,
                                includeDescendants = true,
                                fileTypeFilters = fileTypeFilters,
                                pageSize = pageSize,
                                results = results,
                            )
                        }
                    }
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }
}

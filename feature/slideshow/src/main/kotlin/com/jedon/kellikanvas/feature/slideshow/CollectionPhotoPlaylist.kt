package com.jedon.kellikanvas.feature.slideshow

import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.diagnosticSummary
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.CancellationException

/** Merged playlist plus what happened to each configured root while building it. */
data class CollectionPlaylistResult(
    val photos: List<AssetRef>,
    val rootOutcomes: List<PlaylistRootOutcome>,
) {
    val failedRoots: List<PlaylistRootOutcome.Failed>
        get() = rootOutcomes.filterIsInstance<PlaylistRootOutcome.Failed>()
}

sealed interface PlaylistRootOutcome {
    val root: SelectedRoot

    data class Loaded(
        override val root: SelectedRoot,
        val photoCount: Int,
    ) : PlaylistRootOutcome

    /** [reason] is a `"<ExceptionType>: <message>"` summary of the source failure. */
    data class Failed(
        override val root: SelectedRoot,
        val reason: String,
    ) : PlaylistRootOutcome {
        fun userMessage(): String = "Photo folder '${root.displayLabel}' failed: $reason"
    }
}

object CollectionPhotoPlaylist {
    private const val TAG = "CollectionPhotoPlaylist"
    private const val MAX_PHOTOS_LIMIT = 5000

    suspend fun build(
        adapters: Map<SourceProfileId, SourceAdapter>,
        roots: List<SelectedRoot>,
        pageSize: Int = 64,
    ): CollectionPlaylistResult {
        val results = mutableListOf<AssetRef>()
        val outcomes = mutableListOf<PlaylistRootOutcome>()

        for (root in roots) {
            val adapter = adapters[root.profileId]
            if (adapter == null) {
                DiagLog.w(TAG, "No adapter for root '${root.displayLabel}' (${root.profileId.value})")
                outcomes += PlaylistRootOutcome.Failed(root, "Source not connected")
                continue
            }
            val sizeBefore = results.size
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
                if (throwable is CancellationException) {
                    throw throwable
                }
                if (throwable is IllegalStateException &&
                    throwable.message?.contains("Playlist size limit exceeded") == true
                ) {
                    throw throwable
                }
                DiagLog.w(
                    TAG,
                    "Skipping root '${root.displayLabel}' after listing failure",
                    throwable,
                )
                outcomes += PlaylistRootOutcome.Failed(root, throwable.diagnosticSummary())
            }.onSuccess {
                outcomes += PlaylistRootOutcome.Loaded(root, photoCount = results.size - sizeBefore)
            }
        }

        return CollectionPlaylistResult(photos = results, rootOutcomes = outcomes)
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

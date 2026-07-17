package com.jedon.kellikanvas.source

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus

const val DEFAULT_PAGE_LIMIT: Int = 100
const val MIN_PAGE_LIMIT: Int = 1
const val MAX_PAGE_LIMIT: Int = 500

/**
 * Source-neutral access to one configured source profile.
 *
 * Page limits are validated before implementations perform I/O. Cancellation exceptions from
 * implementations propagate unchanged.
 */
abstract class SourceAdapter {
    abstract val profileId: SourceProfileId
    abstract val kind: SourceKind
    abstract val capabilities: SourceCapabilities

    abstract suspend fun probe(): SourceStatus

    final suspend fun listChildren(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): Page<SourceEntry> {
        validatePageLimit(limit)
        return listChildrenPage(folder, cursor, limit)
    }

    protected abstract suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry>

    abstract suspend fun metadata(asset: AssetRef): PhotoMetadata

    abstract suspend fun open(asset: AssetRef): PhotoByteStream
}

private fun validatePageLimit(limit: Int) {
    require(limit in MIN_PAGE_LIMIT..MAX_PAGE_LIMIT) {
        "Page limit must be between $MIN_PAGE_LIMIT and $MAX_PAGE_LIMIT"
    }
}

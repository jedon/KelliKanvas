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
 * Implementations validate [limit] with [validatePageLimit] before performing I/O and let
 * cancellation exceptions propagate unchanged.
 */
interface SourceAdapter {
    val profileId: SourceProfileId
    val kind: SourceKind
    val capabilities: SourceCapabilities

    suspend fun probe(): SourceStatus

    suspend fun listChildren(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): Page<SourceEntry>

    suspend fun metadata(asset: AssetRef): PhotoMetadata

    suspend fun open(asset: AssetRef): PhotoByteStream
}

fun validatePageLimit(limit: Int): Int {
    require(limit in MIN_PAGE_LIMIT..MAX_PAGE_LIMIT) {
        "Page limit must be between $MIN_PAGE_LIMIT and $MAX_PAGE_LIMIT"
    }
    return limit
}

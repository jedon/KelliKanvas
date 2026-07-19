package com.jedon.kellikanvas.source

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus

/**
 * Fail-closed adapter for source kinds that are catalogued but not implemented yet.
 *
 * Every operation throws [SourceFailure.ProtocolFailure]; none return success and none report
 * authentication requirements.
 */
open class UnsupportedSourceAdapter(
    override val kind: SourceKind,
    override val profileId: SourceProfileId,
    private val reason: String = "${kind.name} source is not implemented",
) : SourceAdapter() {
    override val capabilities: SourceCapabilities = SourceCapabilities()

    override suspend fun probe(): SourceStatus = unsupported(OPERATION_PROBE)

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> = unsupported(OPERATION_LIST)

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = unsupported(OPERATION_METADATA)

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = unsupported(OPERATION_OPEN)

    private fun unsupported(operation: String): Nothing {
        throw SourceFailure.ProtocolFailure(
            profileId = profileId,
            operation = operation,
            safeDetail = reason,
        )
    }

    companion object {
        const val OPERATION_PROBE = "probe"
        const val OPERATION_LIST = "list children"
        const val OPERATION_METADATA = "metadata"
        const val OPERATION_OPEN = "open"
    }
}

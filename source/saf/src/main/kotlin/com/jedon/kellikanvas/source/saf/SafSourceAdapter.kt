package com.jedon.kellikanvas.source.saf

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

class SafSourceAdapter(
    val profile: SafProfile,
    private val documents: SafDocuments,
) : SourceAdapter() {
    override val profileId: SourceProfileId = profile.id
    override val kind: SourceKind = SourceKind.SAF
    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            supportsPaging = true,
            supportsReliableModifiedTime = false,
        )

    val root: FolderRef = FolderRef(profileId, ProviderObjectId(profile.grant.documentId))

    override suspend fun probe(): SourceStatus = mapFailures(OPERATION_PROBE) {
        val rootDocument =
            documents.document(profile.grant.treeUri, profile.grant.documentId)
                ?: throw SourceFailure.SourceUnavailable(profileId, OPERATION_PROBE)
        if (!rootDocument.isFolder) {
            throw SourceFailure.SourceUnavailable(profileId, OPERATION_PROBE)
        }
        SourceStatus(
            available = true,
            summary = "SAF tree available",
        )
    }

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> {
        val offset = decodeCursor(cursor)
        return mapFailures(OPERATION_LIST) {
            val entries =
                documents
                    .children(
                        treeUri = profile.grant.treeUri,
                        parentDocumentId = folder.objectId.value,
                    ).asSequence()
                    .filter { it.isFolder || it.isPhoto }
                    .sortedWith(compareBy<SafDocument>({ it.displayName }, { it.documentId }))
                    .map(::toEntry)
                    .toList()
            require(offset <= entries.size) { "SAF page cursor is outside this folder" }
            val end = minOf(offset + limit, entries.size)
            Page(
                items = entries.subList(offset, end),
                nextCursor = if (end < entries.size) encodeCursor(end) else null,
            )
        }
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = mapFailures(OPERATION_METADATA) {
        val document =
            documents.document(profile.grant.treeUri, asset.objectId.value)
                ?: throw SourceFailure.NotFound(profileId, OPERATION_METADATA)
        if (!document.isPhoto) throw SourceFailure.NotFound(profileId, OPERATION_METADATA)
        PhotoMetadata(asset = document.toAsset())
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = mapFailures(OPERATION_OPEN) {
        if (!asset.mimeType.startsWith("image/")) {
            throw SourceFailure.NotFound(profileId, OPERATION_OPEN)
        }
        val opened =
            documents.openRead(
                treeUri = profile.grant.treeUri,
                documentId = asset.objectId.value,
            )
        try {
            kotlin.coroutines.coroutineContext.ensureActive()
            SafPhotoByteStream(opened, asset.byteLength)
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    private fun toEntry(document: SafDocument): SourceEntry = if (document.isFolder) {
        SourceEntry.Folder(
            ref = FolderRef(profileId, ProviderObjectId(document.documentId)),
            name = document.displayName,
        )
    } else {
        SourceEntry.Photo(
            asset = document.toAsset(),
            name = document.displayName,
        )
    }

    private fun SafDocument.toAsset(): AssetRef = AssetRef(
        profileId = profileId,
        objectId = ProviderObjectId(documentId),
        mimeType = mimeType,
        byteLength = size,
        modifiedAtMillis = modifiedAtMillis,
    )

    private fun encodeCursor(offset: Int): PageCursor {
        val plain = "$CURSOR_VERSION:$offset".toByteArray(StandardCharsets.UTF_8)
        return PageCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(plain))
    }

    private fun decodeCursor(cursor: PageCursor?): Int {
        if (cursor == null) return 0
        val plain =
            try {
                String(Base64.getUrlDecoder().decode(cursor.value), StandardCharsets.UTF_8)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid SAF page cursor", failure)
            }
        val parts = plain.split(':')
        require(parts.size == 2 && parts[0] == CURSOR_VERSION) { "Invalid SAF page cursor" }
        return requireNotNull(parts[1].toIntOrNull()?.takeIf { it >= 0 }) {
            "Invalid SAF page cursor"
        }
    }

    private suspend fun <T> mapFailures(
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: SourceFailure) {
        throw failure
    } catch (failure: SecurityException) {
        throw SourceFailure.PermissionRevoked(profileId, operation)
    } catch (failure: FileNotFoundException) {
        throw SourceFailure.NotFound(profileId, operation)
    } catch (failure: IOException) {
        throw SourceFailure.SourceUnavailable(profileId, operation)
    }

    override fun toString(): String = "SafSourceAdapter(profileId=$profileId)"

    private companion object {
        const val CURSOR_VERSION = "saf-page-v1"
        const val OPERATION_PROBE = "probe"
        const val OPERATION_LIST = "list children"
        const val OPERATION_METADATA = "metadata"
        const val OPERATION_OPEN = "open"
    }
}

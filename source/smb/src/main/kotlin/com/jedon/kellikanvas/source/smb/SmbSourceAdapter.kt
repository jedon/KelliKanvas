package com.jedon.kellikanvas.source.smb

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.coroutines.coroutineContext

interface SmbBackend {
    suspend fun probe()
    suspend fun list(path: String): List<SmbEntry>
    suspend fun metadata(path: String): SmbEntry
    suspend fun open(path: String): PhotoByteStream
}

data class SmbEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String?,
)

class SmbSourceAdapter(
    private val profile: SmbProfile,
    private val backend: SmbBackend,
) : SourceAdapter() {
    override val profileId: SourceProfileId = profile.id
    override val kind: SourceKind = SourceKind.SMB
    override val capabilities =
        SourceCapabilities(
            supportsPaging = true,
            supportsReliableModifiedTime = true,
            supportsETag = false,
            supportsVersionToken = false,
        )

    val root: FolderRef = FolderRef(profileId, ProviderObjectId(ROOT_OBJECT_ID))

    override suspend fun probe(): SourceStatus = mapFailures(OPERATION_PROBE) {
        backend.probe()
        SourceStatus(available = true, summary = "SMB share available")
    }

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> {
        val offset = decodeCursor(cursor)
        return mapFailures(OPERATION_LIST) {
            val path = decodeObjectPath(folder.objectId.value)
            val entries =
                backend.list(path)
                    .asSequence()
                    .filter { it.isDirectory || it.mimeType != null }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }, { it.path }))
                    .map(::toEntry)
                    .toList()
            require(offset <= entries.size) { "SMB page cursor is outside this folder" }
            val end = minOf(offset + limit, entries.size)
            Page(
                items = entries.subList(offset, end),
                nextCursor = if (end < entries.size) encodeCursor(end) else null,
            )
        }
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = mapFailures(OPERATION_METADATA) {
        val path = decodeObjectPath(asset.objectId.value)
        val entry = backend.metadata(path)
        if (entry.isDirectory || entry.mimeType == null) {
            throw SourceFailure.NotFound(profileId, OPERATION_METADATA)
        }
        PhotoMetadata(asset = entry.toAsset())
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = mapFailures(OPERATION_OPEN) {
        if (!asset.mimeType.startsWith("image/")) {
            throw SourceFailure.NotFound(profileId, OPERATION_OPEN)
        }
        backend.open(decodeObjectPath(asset.objectId.value))
    }

    private fun toEntry(entry: SmbEntry): SourceEntry =
        if (entry.isDirectory) {
            SourceEntry.Folder(
                ref = FolderRef(profileId, ProviderObjectId(encodeObjectPath(entry.path))),
                name = entry.name,
            )
        } else {
            SourceEntry.Photo(asset = entry.toAsset(), name = entry.name)
        }

    private fun SmbEntry.toAsset(): AssetRef =
        AssetRef(
            profileId = profileId,
            objectId = ProviderObjectId(encodeObjectPath(path)),
            mimeType = requireNotNull(mimeType),
            byteLength = size,
            modifiedAtMillis = modifiedAtMillis,
        )

    private fun encodeObjectPath(path: String): String {
        val normalized = SmbPath.normalize(path)
        return normalized.ifEmpty { ROOT_OBJECT_ID }
    }

    private fun decodeObjectPath(objectId: String): String {
        val trimmed = objectId.trim()
        if (trimmed == ROOT_OBJECT_ID || trimmed.isEmpty()) return ""
        return SmbPath.normalize(trimmed)
    }

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
                throw IllegalArgumentException("Invalid SMB page cursor", failure)
            }
        val parts = plain.split(':')
        require(parts.size == 2 && parts[0] == CURSOR_VERSION) { "Invalid SMB page cursor" }
        return requireNotNull(parts[1].toIntOrNull()?.takeIf { it >= 0 }) {
            "Invalid SMB page cursor"
        }
    }

    private suspend fun <T> mapFailures(
        operation: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SourceFailure) {
            throw failure
        } catch (failure: Exception) {
            throw SmbFailureMapper.map(profileId, operation, failure)
        }

    companion object {
        const val ROOT_OBJECT_ID: String = "."
        private const val CURSOR_VERSION = "1"
        private const val OPERATION_PROBE = "probe"
        private const val OPERATION_LIST = "list"
        private const val OPERATION_METADATA = "metadata"
        private const val OPERATION_OPEN = "open"

        fun network(
            profile: SmbProfile,
            credentials: SmbCredentials,
        ): SmbSourceAdapter = SmbSourceAdapter(profile, SmbjBackend(profile, credentials))
    }
}

class SmbjBackend(
    private val profile: SmbProfile,
    private val credentials: SmbCredentials,
) : SmbBackend {
    override suspend fun probe() {
        withShare { share ->
            share.list("")
        }
    }

    override suspend fun list(path: String): List<SmbEntry> =
        withShare { share ->
            val smbPath = toSmbRelative(path)
            share.list(smbPath)
                .asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .map { info -> toEntry(path, info) }
                .toList()
        }

    override suspend fun metadata(path: String): SmbEntry =
        withShare { share ->
            val smbPath = toSmbRelative(path)
            val info = share.getFileInformation(smbPath)
            val name = SmbPath.displayName(path).ifEmpty { profile.share }
            val isDir = info.standardInformation.isDirectory
            SmbEntry(
                path = SmbPath.normalize(path),
                name = name,
                isDirectory = isDir,
                size = if (isDir) null else info.standardInformation.endOfFile,
                modifiedAtMillis = info.basicInformation.changeTime.toEpochMillis(),
                mimeType = if (isDir) null else SmbMime.mimeForFileName(name),
            )
        }

    override suspend fun open(path: String): PhotoByteStream {
        coroutineContext.ensureActive()
        val scope = SmbSessionScope.open(profile, credentials)
        return try {
            val smbPath = toSmbRelative(path)
            val file = scope.share.openFile(
                smbPath,
                setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ),
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val input = file.inputStream
            val length = runCatching { file.fileInformation.standardInformation.endOfFile }.getOrNull()
            SmbPhotoByteStream(input, length) {
                try {
                    file.close()
                } finally {
                    scope.close()
                }
            }
        } catch (failure: Throwable) {
            scope.close()
            throw failure
        }
    }

    private suspend fun <T> withShare(block: (com.hierynomus.smbj.share.DiskShare) -> T): T =
        withContext(Dispatchers.IO) {
            SmbSessionScope.open(profile, credentials).use { scope ->
                block(scope.share)
            }
        }

    private fun toEntry(
        parentPath: String,
        info: FileIdBothDirectoryInformation,
    ): SmbEntry {
        val name = info.fileName
        val childPath = SmbPath.join(parentPath, name)
        val isDir = info.fileAttributes.toInt() and FILE_ATTRIBUTE_DIRECTORY != 0
        return SmbEntry(
            path = childPath,
            name = name,
            isDirectory = isDir,
            size = if (isDir) null else info.endOfFile,
            modifiedAtMillis = info.changeTime.toEpochMillis(),
            mimeType = if (isDir) null else SmbMime.mimeForFileName(name),
        )
    }

    private fun toSmbRelative(path: String): String = SmbPath.normalize(path).replace('/', '\\')

    private companion object {
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
    }
}


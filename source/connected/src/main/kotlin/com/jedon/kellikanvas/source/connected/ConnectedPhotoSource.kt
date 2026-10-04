package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID

abstract class ConnectedPhotoSource(override val profileId: SourceProfileId, protected val config: ConnectorConfiguration) : SourceAdapter() {
    override val kind = config.provider.kind
    override val capabilities = SourceCapabilities(supportsPaging = true)
    open val rootFolder = FolderRef(profileId, ProviderObjectId("root"))
    private data class Continuation(val folder: String, val limit: Int, val value: String)
    private val cursors = LinkedHashMap<String, Continuation>()
    final override suspend fun listChildrenPage(folder: FolderRef, cursor: PageCursor?, limit: Int): Page<SourceEntry> = safeConnectorOperation(profileId, "list") { listConnectedPage(folder, cursor, limit) }
    protected abstract suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): Page<SourceEntry>
    final override suspend fun openStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream = safeConnectorOperation(profileId, "open") { openPhotoStream(asset) }
    protected abstract suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream
    protected fun continuation(folder: FolderRef, cursor: PageCursor?, limit: Int): String? {
        if (cursor == null) return null
        val value = synchronized(cursors) { cursors[cursor.value] }
        if (value == null || value.folder != folder.objectId.value || value.limit != limit) throw SourceFailure.ProtocolFailure(profileId, "list", "This page expired. Reopen the folder")
        return value.value
    }
    protected fun page(folder: FolderRef, limit: Int, items: List<SourceEntry>, next: String?): Page<SourceEntry> {
        val cursor = next?.let {
            val key = UUID.randomUUID().toString()
            synchronized(cursors) {
                cursors[key] = Continuation(folder.objectId.value, limit, it)
                while (cursors.size > 256) cursors.remove(cursors.keys.first())
            }
            PageCursor(key)
        }
        return Page(items, cursor)
    }
    protected fun folder(id: String, name: String) = SourceEntry.Folder(FolderRef(profileId, ProviderObjectId(id)), name.ifBlank { "Album" })
    protected fun photo(id: String, name: String, size: Long? = null, modified: Long? = null, mime: String = photoMime(name)) = SourceEntry.Photo(AssetRef(profileId, ProviderObjectId(id), mime, size?.takeIf { it >= 0 }, modified?.takeIf { it >= 0 }), name.ifBlank { "Photo" })
    override suspend fun metadataFor(asset: AssetRef) = PhotoMetadata(asset)
    override suspend fun probe(): SourceStatus {
        listChildren(rootFolder, null, 1)
        return SourceStatus(true, "${config.provider.title} connected")
    }
}

fun connectedPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient): ConnectedPhotoSource = when (config.provider.kind) {
    com.jedon.kellikanvas.model.SourceKind.JELLYFIN, com.jedon.kellikanvas.model.SourceKind.EMBY -> JellyfinPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.IMMICH -> ImmichPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.PLEX -> PlexPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.WEBDAV -> WebDavPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.DROPBOX -> DropboxPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.ONEDRIVE -> OneDrivePhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.BOX -> BoxPhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.S3 -> S3PhotoSource(id, config, client)
    com.jedon.kellikanvas.model.SourceKind.FLICKR -> FlickrPhotoSource(id, config, client)
    else -> error("Unsupported connector")
}

internal fun HttpUrl.path(vararg segments: String): HttpUrl = newBuilder().apply { segments.forEach(::addPathSegment) }.build()
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun jsonBody(data: JsonObject) = data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
internal fun instant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
internal fun photoMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heif"
    "bmp" -> "image/bmp"
    "tif", "tiff" -> "image/tiff"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}

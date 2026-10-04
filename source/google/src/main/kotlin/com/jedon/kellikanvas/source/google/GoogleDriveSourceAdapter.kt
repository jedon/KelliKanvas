package com.jedon.kellikanvas.source.google

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
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.UUID

private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** Drive v3 REST, matching NexusWebClient's readonly account authorization and folder queries. */
class GoogleDriveSourceAdapter(
    override val profileId: SourceProfileId,
    private val http: GoogleHttp,
    private val endpoint: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
) : SourceAdapter() {
    override val kind = SourceKind.GOOGLE_DRIVE
    override val capabilities = SourceCapabilities(supportsPaging = true, supportsReliableModifiedTime = true)
    val rootFolder get() = FolderRef(profileId, ProviderObjectId("root"))
    private data class Cursor(val folder: String, val limit: Int, val token: String)
    private val cursors = LinkedHashMap<String, Cursor>()

    suspend fun accountName(): String = http.json(endpoint.newBuilder().addPathSegment("about").addQueryParameter("fields", "user(emailAddress)").build())
        .let { (it["user"] as? JsonObject)?.text("emailAddress").orEmpty() }
        .ifBlank { throw SourceFailure.ProtocolFailure(profileId, "connect", "Google did not identify the selected account") }

    override suspend fun probe(): SourceStatus {
        accountName()
        return SourceStatus(true, "Google Drive connected")
    }

    suspend fun folder(id: String): SourceEntry.Folder {
        if (id == "root") return SourceEntry.Folder(rootFolder, "My Drive")
        val file = file(id)
        if (file.text("mimeType") != FOLDER_MIME) throw SourceFailure.NotFound(profileId, "list", "Choose a Google Drive folder")
        return SourceEntry.Folder(FolderRef(profileId, ProviderObjectId(file.text("id"))), file.text("name"))
    }

    override suspend fun listChildrenPage(folder: FolderRef, cursor: PageCursor?, limit: Int): Page<SourceEntry> {
        val id = folder.objectId.value
        val previous = cursor?.let { synchronized(cursors) { cursors[it.value] } }
        if (cursor != null && (previous == null || previous.folder != id || previous.limit != limit)) {
            throw SourceFailure.ProtocolFailure(profileId, "list", "This page has expired. Reopen the folder")
        }
        val parent = id.replace("\\", "\\\\").replace("'", "\\'")
        val url = endpoint.newBuilder().addPathSegment("files")
            .addQueryParameter("q", "'$parent' in parents and trashed = false and (mimeType = '$FOLDER_MIME' or mimeType contains 'image/')")
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,imageMediaMetadata(width,height,time))")
            .addQueryParameter("pageSize", limit.toString())
            .addQueryParameter("supportsAllDrives", "true").addQueryParameter("includeItemsFromAllDrives", "true")
            .addQueryParameter("orderBy", "folder,name_natural")
            .apply { previous?.let { addQueryParameter("pageToken", it.token) } }.build()
        val page = http.json(url)
        val entries = (page["files"] as? JsonArray).orEmpty().mapNotNull { item ->
            val data = item as? JsonObject ?: return@mapNotNull null
            val mime = data.text("mimeType")
            val objectId = data.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = data.text("name").ifBlank { "Photo" }
            if (mime == FOLDER_MIME) {
                SourceEntry.Folder(FolderRef(profileId, ProviderObjectId(objectId)), name)
            } else if (mime.startsWith("image/")) {
                val metadata = data["imageMediaMetadata"] as? JsonObject
                SourceEntry.Photo(asset(data), name, metadata?.positiveInt("width"), metadata?.positiveInt("height"))
            } else {
                null
            }
        }
        val token = page.text("nextPageToken")
        if (token.isNotBlank() && token == previous?.token) throw SourceFailure.ProtocolFailure(profileId, "list", "Google repeated a page")
        val next = token.takeIf { it.isNotBlank() }?.let {
            val opaque = UUID.randomUUID().toString()
            synchronized(cursors) {
                cursors[opaque] = Cursor(id, limit, it)
                while (cursors.size > 256) cursors.remove(cursors.keys.first())
            }
            PageCursor(opaque)
        }
        return Page(entries, next)
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata {
        val data = file(asset.objectId.value)
        val metadata = data["imageMediaMetadata"] as? JsonObject
        return PhotoMetadata(asset(data), metadata?.positiveInt("width"), metadata?.positiveInt("height"), metadata?.text("time")?.instantMillis())
    }

    override suspend fun openStream(asset: AssetRef) = http.open(
        endpoint.newBuilder().addPathSegment("files").addPathSegment(asset.objectId.value)
            .addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true").build(),
    )

    private suspend fun file(id: String) = http.json(
        endpoint.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,imageMediaMetadata(width,height,time)")
            .addQueryParameter("supportsAllDrives", "true").build(),
    )

    private fun asset(data: JsonObject) = AssetRef(
        profileId,
        ProviderObjectId(data.text("id")),
        data.text("mimeType"),
        data["size"]?.jsonPrimitive?.longOrNull,
        data.text("modifiedTime").instantMillis(),
    )
}

internal fun JsonObject.positiveInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
internal fun String.instantMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

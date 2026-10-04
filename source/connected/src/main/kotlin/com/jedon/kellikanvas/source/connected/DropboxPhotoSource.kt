package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class DropboxPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, private val client: OkHttpClient, private val api: HttpUrl = config.endpoint, private val content: HttpUrl = "https://content.dropboxapi.com/2/".toHttpUrl()) : ConnectedPhotoSource(id, config) {
    private val http = ConnectorHttp(id, client, { mapOf("Authorization" to "Bearer ${config.secret}") }, api)
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val previous = continuation(folder, cursor, limit)
        val params = if (previous == null) JsonObject(mapOf("path" to JsonPrimitive(folder.objectId.value.takeUnless { it == "root" }.orEmpty()), "limit" to JsonPrimitive(limit), "recursive" to JsonPrimitive(false))) else JsonObject(mapOf("cursor" to JsonPrimitive(previous)))
        val location = if (previous == null) api.path("files", "list_folder") else api.path("files", "list_folder", "continue")
        val data = http.json(location, "POST", jsonBody(params)) as JsonObject
        val entries = data.array("entries").mapNotNull { item ->
            val id = item.text("id").ifBlank { item.text("path_lower") }.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.text("name")
            when (item.text(".tag")) {
                "folder" -> folder(id, name)
                "file" -> if (photoMime(name).startsWith("image/")) photo(id, name, item.number("size"), instant(item.text("server_modified"))) else null
                else -> null
            }
        }
        val next = if (data.text("has_more") == "true") data.text("cursor").takeIf(String::isNotBlank) else null
        if (data.text("has_more") == "true" && (next == null || next == previous)) throw SourceFailure.ProtocolFailure(profileId, "list", "Dropbox returned an invalid page")
        return page(folder, limit, entries, next)
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        val download = ConnectorHttp(profileId, client, { mapOf("Authorization" to "Bearer ${config.secret}", "Dropbox-API-Arg" to JsonObject(mapOf("path" to JsonPrimitive(asset.objectId.value))).toString()) }, content)
        return download.open(content.path("files", "download"), "POST", ByteArray(0).toRequestBody())
    }
}

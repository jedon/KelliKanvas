package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/** Immich v2/v3: album contents come from metadata search, never the removed album.assets field. */
class ImmichPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient) : ConnectedPhotoSource(id, config) {
    private val api = if (config.endpoint.pathSegments.filter(String::isNotEmpty).lastOrNull() == "api") config.endpoint else config.endpoint.path("api", "")
    private val http = ConnectorHttp(id, client, { mapOf("x-api-key" to config.secret) }, api)
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val previous = continuation(folder, cursor, limit)
        if (folder.objectId.value == "root") {
            val albums = http.json(api.path("albums")) as JsonArray
            val entries = listOf(folder("all", "All photos")) + albums.mapNotNull { value ->
                val album = value as? JsonObject ?: return@mapNotNull null
                album.text("id").takeIf { it.isNotBlank() }?.let { folder("album:$it", album.text("albumName")) }
            }
            val offset = previous?.toInt() ?: 0
            return page(folder, limit, entries.drop(offset).take(limit), (offset + limit).takeIf { it < entries.size }?.toString())
        }
        val index = previous?.toInt() ?: 1
        val params = mutableMapOf("type" to JsonPrimitive("IMAGE"), "size" to JsonPrimitive(limit), "page" to JsonPrimitive(index))
        val request = JsonObject(params + if (folder.objectId.value.startsWith("album:")) mapOf("albumIds" to JsonArray(listOf(JsonPrimitive(folder.objectId.value.removePrefix("album:"))))) else emptyMap())
        val data = (http.json(api.path("search", "metadata"), "POST", jsonBody(request)) as JsonObject)["assets"] as JsonObject
        val raw = data.array("items")
        val entries = raw.mapNotNull { asset ->
            if (asset.text("type") != "IMAGE" || asset.text("id").isBlank()) null else photo(asset.text("id"), asset.text("originalFileName"), modified = instant(asset.text("updatedAt")), mime = asset.text("originalMimeType").takeIf { it.startsWith("image/") } ?: photoMime(asset.text("originalFileName")))
        }
        val next = data.text("nextPage").takeIf { it != "null" && it.isNotBlank() }
        if (next != null && (next.toIntOrNull() ?: 0) <= index) throw SourceFailure.ProtocolFailure(profileId, "list", "The server repeated a page. Update Immich and try again")
        return page(folder, limit, entries, next)
    }
    override suspend fun openPhotoStream(asset: AssetRef) = http.open(api.path("assets", asset.objectId.value, "original"))
}

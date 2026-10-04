package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

class PlexPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient) : ConnectedPhotoSource(id, config) {
    private val http = ConnectorHttp(id, client, { mapOf("X-Plex-Token" to config.secret, "Accept" to "application/json", "X-Plex-Container-Size" to "100") }, config.endpoint)
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val offset = continuation(folder, cursor, limit)?.toInt() ?: 0
        if (folder.objectId.value == "root") {
            val data = (http.json(config.endpoint.path("library", "sections")) as JsonObject)["MediaContainer"] as JsonObject
            val entries = data.array("Directory").filter { it.text("type") == "photo" }.map { folder("section:${it.text("key")}", it.text("title")) }
            return page(folder, limit, entries.drop(offset).take(limit), (offset + limit).takeIf { it < entries.size }?.toString())
        }
        val id = folder.objectId.value
        val location = if (id.startsWith("section:")) config.endpoint.path("library", "sections", id.removePrefix("section:"), "all") else config.endpoint.path("library", "metadata", id, "children")
        val url = location.newBuilder().addQueryParameter("X-Plex-Container-Start", offset.toString()).addQueryParameter("X-Plex-Container-Size", limit.toString()).build()
        val data = (http.json(url) as JsonObject)["MediaContainer"] as JsonObject
        val raw = data.array("Metadata")
        val entries = raw.mapNotNull { item ->
            val ratingKey = item.text("ratingKey").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (item.text("type")) {
                "photoalbum" -> folder(ratingKey, item.text("title"))
                "photo" -> {
                    val part = item.array("Media").flatMap { it.array("Part") }.firstOrNull() ?: return@mapNotNull null
                    val key = part.text("key").takeIf { it.startsWith("/library/parts/") } ?: return@mapNotNull null
                    photo(key, item.text("title"), part.number("size"), mime = photoMime(part.text("file")).takeIf { it.startsWith("image/") } ?: "image/jpeg")
                }
                else -> null
            }
        }
        return page(folder, limit, entries, (offset + raw.size).takeIf { raw.isNotEmpty() && it < (data.number("totalSize") ?: data.number("size") ?: 0) }?.toString())
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        require(asset.objectId.value.startsWith("/library/parts/"))
        val url = requireNotNull(config.endpoint.resolve(asset.objectId.value.removePrefix("/")))
        return http.open(url)
    }
}

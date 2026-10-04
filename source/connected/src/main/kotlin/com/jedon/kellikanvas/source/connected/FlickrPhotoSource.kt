package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Public albums only. Does not impersonate a logged-in Flickr user. */
class FlickrPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, private val client: OkHttpClient, private val api: HttpUrl = config.endpoint) : ConnectedPhotoSource(id, config) {
    private val http = ConnectorHttp(id, client, { emptyMap() }, api)
    private suspend fun request(method: String, params: Map<String, String>): JsonObject {
        val url = api.newBuilder().addQueryParameter("method", method).addQueryParameter("api_key", config.secret).addQueryParameter("format", "json").addQueryParameter("nojsoncallback", "1")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val data = http.json(url) as JsonObject
        if (data.text("stat") != "ok") throw SourceFailure.PermissionRevoked(profileId, "connect", "Check your Flickr API key, user ID and public album permissions")
        return data
    }
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val index = continuation(folder, cursor, limit)?.toInt() ?: 1
        val params = mapOf("user_id" to config.username, "page" to index.toString(), "per_page" to limit.toString())
        val root = folder.objectId.value == "root"
        val data = request(if (root) "flickr.photosets.getList" else "flickr.photosets.getPhotos", params + if (root) emptyMap() else mapOf("photoset_id" to folder.objectId.value, "media" to "photos"))
        val listing = data[if (root) "photosets" else "photoset"] as JsonObject
        val entries = listing.array(if (root) "photoset" else "photo").mapNotNull { item ->
            val id = item.text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (root) folder(id, (item["title"] as? JsonObject)?.text("_content").orEmpty()) else photo(id, item.text("title"), mime = "image/jpeg")
        }
        return page(folder, limit, entries, (index + 1).takeIf { index < (listing.number("pages") ?: 1) }?.toString())
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        val data = request("flickr.photos.getSizes", mapOf("photo_id" to asset.objectId.value))
        val sizes = (data["sizes"] as JsonObject).array("size").filter { it.text("media") in setOf("photo", "") }
        val best = sizes.maxByOrNull { (it.number("width") ?: 0) * (it.number("height") ?: 0) } ?: throw SourceFailure.NotFound(profileId, "open", "Flickr has no displayable image")
        val url = best.text("source").toHttpUrl()
        require(url.isHttps && url.host.endsWith(".staticflickr.com") && url.username.isEmpty() && url.password.isEmpty())
        return ConnectorHttp(profileId, client, { emptyMap() }, url).open(url)
    }
}

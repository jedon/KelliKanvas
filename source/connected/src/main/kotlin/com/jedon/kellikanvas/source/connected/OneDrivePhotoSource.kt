package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class OneDrivePhotoSource(id: SourceProfileId, config: ConnectorConfiguration, private val client: OkHttpClient, private val api: HttpUrl = config.endpoint) : ConnectedPhotoSource(id, config) {
    private val http = ConnectorHttp(id, client, { mapOf("Authorization" to "Bearer ${config.secret}") }, api)
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val previous = continuation(folder, cursor, limit)
        val location = if (folder.objectId.value == "root") api.path("me", "drive", "root", "children") else api.path("me", "drive", "items", folder.objectId.value, "children")
        val url = previous?.toHttpUrl() ?: location.newBuilder().addQueryParameter("\$top", limit.toString()).addQueryParameter("\$select", "id,name,folder,file,size,lastModifiedDateTime,image").build()
        require(url.encodedPath.startsWith(api.encodedPath))
        val data = http.json(url) as JsonObject
        val entries = data.array("value").mapNotNull { item ->
            val id = item.text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.text("name")
            if (item["folder"] is JsonObject) {
                folder(id, name)
            } else {
                val mime = (item["file"] as? JsonObject)?.text("mimeType").orEmpty().ifBlank { photoMime(name) }
                if (mime.startsWith("image/")) photo(id, name, item.number("size"), instant(item.text("lastModifiedDateTime")), mime) else null
            }
        }
        val next = data.text("@odata.nextLink").takeIf(String::isNotBlank)
        require(next == null || next != previous) { "The server repeated a page" }
        return page(folder, limit, entries, next)
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        val item = http.json(api.path("me", "drive", "items", asset.objectId.value)) as JsonObject
        val url = item.text("@microsoft.graph.downloadUrl").toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        // Graph supplies a short-lived preauthorized URL. The account bearer token never leaves Graph.
        return ConnectorHttp(profileId, client, { emptyMap() }, url).open(url)
    }
}

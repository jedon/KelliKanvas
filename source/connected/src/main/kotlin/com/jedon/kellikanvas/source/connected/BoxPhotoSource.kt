package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class BoxPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient, private val api: HttpUrl = config.endpoint) : ConnectedPhotoSource(id, config) {
    override val rootFolder = FolderRef(id, ProviderObjectId("0"))
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val http = ConnectorHttp(id, client, { mapOf("Authorization" to "Bearer ${config.secret}") }, api)
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val previous = continuation(folder, cursor, limit)
        val url = api.path("folders", folder.objectId.value, "items").newBuilder().addQueryParameter("usemarker", "true").addQueryParameter("limit", limit.toString()).addQueryParameter("fields", "id,name,type,size,modified_at")
            .apply { previous?.let { addQueryParameter("marker", it) } }.build()
        val data = http.json(url) as JsonObject
        val items = data.array("entries").mapNotNull { item ->
            val id = item.text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            when (item.text("type")) {
                "folder" -> folder(id, item.text("name"))
                "file" -> if (photoMime(item.text("name")).startsWith("image/")) photo(id, item.text("name"), item.number("size"), instant(item.text("modified_at"))) else null
                else -> null
            }
        }
        val next = data.text("next_marker").takeUnless { it.isBlank() || it == "null" }
        require(next == null || next != previous)
        return page(folder, limit, items, next)
    }
    override suspend fun openPhotoStream(asset: AssetRef) = safeConnectorOperation(profileId, "open") {
        val request = Request.Builder().url(api.path("files", asset.objectId.value, "content")).header("Authorization", "Bearer ${config.secret}").build()
        val call = client.newCall(request)
        val url = call.awaitConnectorResponse().use { response ->
            if (response.code != 302) throw connectorStatusFailure(profileId, response.code)
            response.header("Location")?.toHttpUrl() ?: throw SourceFailure.ProtocolFailure(profileId, "open", "Box did not provide a download link")
        }
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        ConnectorHttp(profileId, client, { emptyMap() }, url).open(url)
    }
}

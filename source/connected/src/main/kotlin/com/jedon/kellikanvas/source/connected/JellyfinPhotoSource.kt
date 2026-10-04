package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/** Shared read-only photo API supported by Jellyfin and Emby. */
class JellyfinPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient) : ConnectedPhotoSource(id, config) {
    private val mutex = Mutex()
    private var token = ""
    private var userId = ""
    private val device = id.value.replace(Regex("[^A-Za-z0-9_-]"), "")
    private val clientHeader = "MediaBrowser Client=\"KelliKanvas\", Device=\"Art TV\", DeviceId=\"$device\", Version=\"1.0\""
    private val http = ConnectorHttp(id, client, { mapOf("Authorization" to clientHeader, "X-Emby-Token" to token) }, config.endpoint)
    private suspend fun authenticate() = mutex.withLock {
        if (token.isEmpty()) {
            val data = http.json(config.endpoint.path("Users", "AuthenticateByName"), "POST", jsonBody(JsonObject(mapOf("Username" to JsonPrimitive(config.username), "Pw" to JsonPrimitive(config.secret))))) as JsonObject
            val nextUser = (data["User"] as? JsonObject)?.text("Id").orEmpty()
            val nextToken = data.text("AccessToken")
            if (nextUser.isBlank() || nextToken.isBlank()) throw SourceFailure.AuthenticationRequired(profileId, "connect", "Check your media server login")
            userId = nextUser
            token = nextToken
        }
    }
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<com.jedon.kellikanvas.model.SourceEntry> {
        authenticate()
        val start = continuation(folder, cursor, limit)?.toInt() ?: 0
        val url = config.endpoint.path("Items").newBuilder()
            .addQueryParameter("UserId", userId).addQueryParameter("Recursive", "false")
            .addQueryParameter("IncludeItemTypes", "Photo,PhotoAlbum,Folder,CollectionFolder")
            .addQueryParameter("SortBy", "SortName").addQueryParameter("SortOrder", "Ascending")
            .addQueryParameter("StartIndex", start.toString()).addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", "DateCreated,MediaSources")
            .apply { if (folder.objectId.value != "root") addQueryParameter("ParentId", folder.objectId.value) }.build()
        val data = http.json(url) as JsonObject
        val raw = data.array("Items")
        val entries = raw.mapNotNull { item ->
            val itemId = item.text("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.text("Name").ifBlank { "Photo" }
            if (item.text("Type") == "Photo") {
                val path = item.text("Path").ifBlank { item.array("MediaSources").firstOrNull()?.text("Path").orEmpty() }
                photo(itemId, name, modified = instant(item.text("DateCreated")), mime = photoMime(path).takeIf { it.startsWith("image/") } ?: "image/jpeg")
            } else {
                folder(itemId, name)
            }
        }
        return page(folder, limit, entries, (start + raw.size).takeIf { raw.isNotEmpty() && it < (data.number("TotalRecordCount") ?: 0) }?.toString())
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        authenticate()
        return http.open(config.endpoint.path("Items", asset.objectId.value, "Download"))
    }
}

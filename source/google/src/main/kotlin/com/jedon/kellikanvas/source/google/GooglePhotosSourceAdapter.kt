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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class GoogleAmbientDevice(val id: String, val settingsUri: String, val sourcesSet: Boolean, val pollSeconds: Long) {
    override fun toString() = "GoogleAmbientDevice(<redacted>)"
}

class GooglePhotosSourceAdapter(
    override val profileId: SourceProfileId,
    val deviceId: String,
    private val http: GoogleHttp,
    private val endpoint: HttpUrl = "https://photosambient.googleapis.com/v1/".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
) : SourceAdapter() {
    override val kind = SourceKind.GOOGLE_PHOTOS
    override val capabilities = SourceCapabilities(supportsPaging = true)
    val rootFolder get() = FolderRef(profileId, ProviderObjectId("ambient"))
    private data class Photo(val entry: SourceEntry.Photo, val url: HttpUrl)
    private data class Cursor(val offset: Int, val limit: Int, val revision: Long)
    private val mutex = Mutex()
    private var photos: List<Photo> = emptyList()
    private var fetchedAt: Long? = null
    private var revision = 0L
    private val cursors = LinkedHashMap<String, Cursor>()

    suspend fun device(): GoogleAmbientDevice = parseDevice(http.json(endpoint.newBuilder().addPathSegment("devices").addPathSegment(deviceId).build()))
    suspend fun deleteDevice() = http.delete(endpoint.newBuilder().addPathSegment("devices").addPathSegment(deviceId).build())

    override suspend fun probe(): SourceStatus = SourceStatus(device().sourcesSet, "Google Photos connected")

    override suspend fun listChildrenPage(folder: FolderRef, cursor: PageCursor?, limit: Int): Page<SourceEntry> = mutex.withLock {
        require(folder.objectId == rootFolder.objectId) { "Unknown Google Photos folder" }
        val previous = cursor?.let { cursors[it.value] }
        if (cursor != null && (previous == null || previous.limit != limit || previous.revision != revision)) {
            throw SourceFailure.ProtocolFailure(profileId, "list", "This page has expired. Reopen Google Photos")
        }
        if (cursor == null) refreshSnapshot()
        val offset = previous?.offset ?: 0
        val entries = photos.drop(offset).take(limit).map { it.entry }
        val nextOffset = offset + entries.size
        val next = if (nextOffset < photos.size) {
            val opaque = UUID.randomUUID().toString()
            cursors[opaque] = Cursor(nextOffset, limit, revision)
            while (cursors.size > 256) cursors.remove(cursors.keys.first())
            PageCursor(opaque)
        } else {
            null
        }
        Page(entries, next)
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = mutex.withLock {
        val entry = find(asset).entry
        PhotoMetadata(entry.asset, entry.width, entry.height)
    }

    override suspend fun openStream(asset: AssetRef) = http.open(mutex.withLock { find(asset).url })

    private suspend fun find(asset: AssetRef): Photo {
        // Snapshot URLs are memory-only and refreshed well before their short lifetime.
        if (fetchedAt == null || now() - fetchedAt!! >= URL_REFRESH_MILLIS) refreshSnapshot()
        return photos.firstOrNull { it.entry.asset.key == asset.key }
            ?: throw SourceFailure.NotFound(profileId, "open", "This photo is no longer in your Google Photos selection")
    }

    private suspend fun refreshSnapshot() {
        if (fetchedAt != null && now() - fetchedAt!! < SNAPSHOT_REFRESH_MILLIS) return
        val result = http.json(
            endpoint.newBuilder().addPathSegment("mediaItems").addQueryParameter("deviceId", deviceId)
                .addQueryParameter("pageSize", "100").build(),
        )
        val next = (result["mediaItems"] as? JsonArray).orEmpty().mapNotNull { item ->
            val data = item as? JsonObject ?: return@mapNotNull null
            val file = data["mediaFile"] as? JsonObject ?: return@mapNotNull null
            val mime = file.text("mimeType")
            if (!mime.startsWith("image/")) return@mapNotNull null
            val id = data.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = file.text("baseUrl").toHttpUrl()
            if (!url.isHttps || !(url.host == "googleusercontent.com" || url.host.endsWith(".googleusercontent.com"))) {
                throw SourceFailure.ProtocolFailure(profileId, "open", "Google returned an invalid photo location")
            }
            val metadata = file["mediaFileMetadata"] as? JsonObject
            Photo(
                SourceEntry.Photo(
                    AssetRef(profileId, ProviderObjectId(id), mime),
                    "Google Photos photo",
                    metadata?.positiveInt("width"),
                    metadata?.positiveInt("height"),
                ),
                (url.toString() + "=w3840-h3840").toHttpUrl(),
            )
        }.distinctBy { it.entry.asset.key }.take(100)
        photos = next
        fetchedAt = now()
        revision++
        cursors.clear()
    }

    companion object {
        const val SNAPSHOT_REFRESH_MILLIS = 10 * 60_000L
        private const val URL_REFRESH_MILLIS = 45 * 60_000L
        suspend fun createDevice(http: GoogleHttp, requestId: String, endpoint: HttpUrl = "https://photosambient.googleapis.com/v1/".toHttpUrl()): GoogleAmbientDevice = parseDevice(
            http.json(
                endpoint.newBuilder().addPathSegment("devices").addQueryParameter("requestId", requestId).build(),
                "POST",
                JsonObject(mapOf("displayName" to JsonPrimitive("KelliKanvas"))).toString().toRequestBody("application/json".toMediaType()),
            ),
        )
        private fun parseDevice(json: JsonObject): GoogleAmbientDevice {
            val id = json.text("id").also { require(it.isNotBlank()) }
            val settings = json.text("settingsUri").also { requireGoogleLink(it) }
            val polling = (json["pollingConfig"] as? JsonObject)?.text("pollInterval")?.removeSuffix("s")?.toDoubleOrNull()?.toLong()?.coerceIn(5, 60) ?: 5
            return GoogleAmbientDevice(id, settings, json["mediaSourcesSet"]?.jsonPrimitive?.booleanOrNull == true, polling)
        }
    }
}

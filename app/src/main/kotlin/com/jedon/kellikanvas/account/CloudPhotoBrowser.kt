package com.jedon.kellikanvas.account

import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.connected.ConnectedPhotoSource
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.PhotoConnector
import com.jedon.kellikanvas.source.connected.connectedPhotoSource
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbProfile
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.security.MessageDigest

/** Credentials stay on the linked TV; only folder names and opaque IDs return to the phone. */
internal class CloudPhotoBrowser(private val client: OkHttpClient) {
    private var cachedKey: List<Byte>? = null
    private var cachedSource: ConnectedPhotoSource? = null
    private var lastUsed = 0L
    fun clear() {
        cachedKey = null
        cachedSource = null
    }
    suspend fun list(work: JsonObject): JsonObject = try {
        withTimeoutOrNull(30_000) {
            val connection = work.getValue("connection").jsonObject
            val id = SourceProfileId(connection.text("id"))
            val config = connection.getValue("configuration").jsonObject
            var credentials: SmbCredentials? = null
            try {
                val source = if (connection.text("provider") == "SMB") {
                    credentials = SmbCredentials(config.text("username"), config.text("secret").toCharArray(), config.text("domain"))
                    SmbSourceAdapter.network(SmbProfile(id, config.text("host"), config.getValue("port").jsonPrimitive.int, config.text("share"), config.text("domain"), config.text("username")), credentials!!)
                } else {
                    val key = MessageDigest.getInstance("SHA-256").digest(connection.toString().toByteArray()).toList()
                    if (cachedKey != key || System.currentTimeMillis() - lastUsed > 300_000) {
                        clear()
                        cachedSource = connectedPhotoSource(id, ConnectorConfiguration(PhotoConnector.valueOf(connection.text("provider")), config.text("endpoint"), config.text("username"), config.text("secret")), client)
                        cachedKey = key
                    }
                    lastUsed = System.currentTimeMillis()
                    cachedSource!!
                }
                val root = when (source) {
                    is SmbSourceAdapter -> source.root
                    is ConnectedPhotoSource -> source.rootFolder
                    else -> error("Unsupported source")
                }
                val objectId = work["objectId"]?.jsonPrimitive?.contentOrNull ?: root.objectId.value
                val cursor = work["cursor"]?.jsonPrimitive?.contentOrNull?.let(::PageCursor)
                val page = source.listChildren(FolderRef(id, ProviderObjectId(objectId)), cursor, 200)
                buildJsonObject {
                    put("objectId", objectId)
                    put("name", connection.text("name").take(256))
                    put(
                        "folders",
                        JsonArray(
                            page.items.filterIsInstance<SourceEntry.Folder>().map {
                                require(it.ref.objectId.value.length <= 2048)
                                buildJsonObject {
                                    put("objectId", it.ref.objectId.value)
                                    put("name", it.name.take(256))
                                }
                            },
                        ),
                    )
                    put("nextCursor", page.nextCursor?.value?.let(::JsonPrimitive) ?: JsonNull)
                    put("photoCount", page.items.count { it is SourceEntry.Photo })
                }
            } finally {
                credentials?.clear()
            }
        } ?: failure("network")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SourceFailure.AuthenticationRequired) {
        failure("authentication")
    } catch (_: SourceFailure.PermissionRevoked) {
        failure("authentication")
    } catch (_: Exception) {
        failure("unavailable")
    }
    private fun failure(error: String) = buildJsonObject {
        put("objectId", "root")
        put("name", "Photo library")
        put("folders", JsonArray(emptyList()))
        put("photoCount", 0)
        put("error", error)
    }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}

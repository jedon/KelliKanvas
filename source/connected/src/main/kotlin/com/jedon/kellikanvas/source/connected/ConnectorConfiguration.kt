package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class PhotoConnector(val title: String, val kind: SourceKind, val category: String, val help: String, val passwordLogin: Boolean = false, val fixedEndpoint: String = "") {
    JELLYFIN("Jellyfin", SourceKind.JELLYFIN, "Media servers", "Your server address and Jellyfin login.", true),
    EMBY("Emby", SourceKind.EMBY, "Media servers", "Your server address and Emby login.", true),
    PLEX("Plex", SourceKind.PLEX, "Media servers", "Your Plex server address and an X-Plex-Token with access to photo libraries."),
    IMMICH("Immich", SourceKind.IMMICH, "Photo libraries", "Your server address and an API key with album.read, asset.read and asset.download permissions."),
    NEXTCLOUD("Nextcloud", SourceKind.WEBDAV, "Files and NAS", "Paste the WebDAV URL shown in Files settings. Use your username and an app password.", true),
    OWNCLOUD("ownCloud", SourceKind.WEBDAV, "Files and NAS", "Your complete WebDAV folder URL, username and app password.", true),
    SYNOLOGY("Synology WebDAV", SourceKind.WEBDAV, "Files and NAS", "Enable WebDAV Server on your NAS. Use the full photo folder URL and your NAS login.", true),
    SEAFILE("Seafile WebDAV", SourceKind.WEBDAV, "Files and NAS", "Enable SeafDAV. Use the full library URL and your account login.", true),
    WEBDAV("WebDAV", SourceKind.WEBDAV, "Files and NAS", "Any compatible WebDAV photo folder, including QNAP and other NAS servers.", true),
    PHOTOPRISM("PhotoPrism WebDAV", SourceKind.WEBDAV, "Photo libraries", "Enable PhotoPrism WebDAV and use your originals folder URL, username and app password.", true),
    DROPBOX("Dropbox", SourceKind.DROPBOX, "Cloud storage", "Use a Dropbox access token with files.metadata.read and files.content.read. Reconnect when the token expires.", fixedEndpoint = "https://api.dropboxapi.com/2/"),
    ONEDRIVE("OneDrive", SourceKind.ONEDRIVE, "Cloud storage", "Use a Microsoft Graph access token with Files.Read. Reconnect when the token expires.", fixedEndpoint = "https://graph.microsoft.com/v1.0/"),
    BOX("Box", SourceKind.BOX, "Cloud storage", "Use a Box access token with read access to your photo folders. Reconnect when the token expires.", fixedEndpoint = "https://api.box.com/2.0/"),
    S3("S3 compatible storage", SourceKind.S3, "Cloud storage", "Use your bucket endpoint, an access key ID and secret key with ListBucket and GetObject access. Supports Amazon S3 and compatible storage such as MinIO. Region is inferred from AWS endpoints; other endpoints use us-east-1.", true),
    FLICKR("Flickr public albums", SourceKind.FLICKR, "Photo sharing", "Use your Flickr user ID (NSID) and API application key to display public albums. Private albums require Flickr OAuth and are not available in this connector.", true, "https://www.flickr.com/services/rest/"),
    ;

    val usernameLabel: String get() = when (this) {
        S3 -> "Access key ID"
        FLICKR -> "Flickr user ID (NSID)"
        else -> "Username"
    }
    val secretLabel: String get() = when (this) {
        S3 -> "Secret access key"
        FLICKR -> "Flickr API key"
        else -> if (passwordLogin) "Password or app password" else "Access token or API key"
    }
    val summary: String get() = when (this) {
        JELLYFIN, EMBY, PLEX -> "Photo libraries and albums from your $title server."
        IMMICH -> "Your self-hosted albums and photo timeline."
        FLICKR -> "Public photography albums from Flickr."
        S3 -> "Photo folders from Amazon S3, MinIO and compatible buckets."
        PHOTOPRISM -> "Original photo folders from your PhotoPrism library."
        else -> "Photo folders from your ${title.removeSuffix(" WebDAV")} storage."
    }
}

/** The entire connection, including server location, lives in the encrypted vault. */
class ConnectorConfiguration(val provider: PhotoConnector, endpoint: String, val username: String, val secret: String) {
    val endpoint: HttpUrl = (provider.fixedEndpoint.ifEmpty { endpoint.trim() }.trimEnd('/') + "/").toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "Use a server URL without login details or query parameters" }
        val privateHost = it.host == "localhost" || it.host == "127.0.0.1" || it.host.matches(Regex("10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d+\\.\\d+"))
        require(it.isHttps || privateHost || !it.host.contains('.') || it.host.endsWith(".local") || it.host.endsWith(".ts.net")) { "Use HTTPS for a remote server" }
    }
    init {
        require(secret.isNotBlank() && secret.length <= 4096) { "Enter the password or access token" }
        require(!provider.passwordLogin || username.isNotBlank()) { "Enter your username" }
    }
    override fun toString() = "ConnectorConfiguration(<redacted>)"
}

class ConnectorStore(private val vault: CredentialVault) {
    fun write(id: SourceProfileId, config: ConnectorConfiguration) {
        val bytes = JsonObject(mapOf("provider" to JsonPrimitive(config.provider.name), "endpoint" to JsonPrimitive(config.endpoint.toString()), "username" to JsonPrimitive(config.username), "secret" to JsonPrimitive(config.secret))).toString().toByteArray()
        try {
            vault.write(id, bytes)
        } finally {
            bytes.fill(0)
        }
    }
    fun read(id: SourceProfileId): ConnectorConfiguration? {
        val read = vault.read(id)
        if (read !is CredentialReadResult.Present) return null
        return read.use {
            val bytes = it.secret.copyBytes()
            try {
                val data = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return@use null
                ConnectorConfiguration(PhotoConnector.valueOf(data.text("provider")), data.text("endpoint"), data.text("username"), data.text("secret"))
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                bytes.fill(0)
            }
        }
    }
    fun remove(id: SourceProfileId) = vault.remove(id)
}

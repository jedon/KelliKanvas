package com.jedon.kellikanvas.account

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import com.jedon.kellikanvas.AppContainer
import com.jedon.kellikanvas.BuildConfig
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.GoogleConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SmbConnection
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.catalog.SourceProfileStatus
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.PhotoConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

class CloudAccount(
    private val database: KelliKanvasDatabase,
    private val preferences: com.jedon.kellikanvas.catalog.preferences.AppPreferencesRepository,
    private val vault: com.jedon.kellikanvas.security.CredentialVault,
    private val httpClient: okhttp3.OkHttpClient,
    private val connectorStore: com.jedon.kellikanvas.source.connected.ConnectorStore,
    context: Context,
    private val defaultOrigin: String = BuildConfig.CLOUD_SERVER_URL,
) {
    constructor(container: AppContainer, context: Context) : this(container.database, container.preferences, container.credentialVault, container.httpClient, container.connectorStore, context)
    private val config = context.getSharedPreferences("kanvas-account", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    val syncStatus = kotlinx.coroutines.flow.MutableStateFlow("Your linked TV automatically receives phone selections.")
    private val phoneBrowser = CloudPhotoBrowser(httpClient)
    suspend fun browseFromPhone() {
        withContext(Dispatchers.IO) {
            if (session() == null) {
                phoneBrowser.clear()
                return@withContext
            }
            val api = api()
            val work = api.nextBrowse() ?: return@withContext
            val result = phoneBrowser.list(work)
            api.completeBrowse(work.getValue("id").jsonPrimitive.content, result)
        }
    }
    val origin: String get() = config.getString("server", defaultOrigin) ?: defaultOrigin
    internal fun session(): JsonObject? = readSecret(ACCOUNT_VAULT_ID)?.let { bytes ->
        try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } finally {
            bytes.fill(0)
        }
    }
    val accountLabel: String? get() = session()?.get("email")?.jsonPrimitive?.content
    private fun api(server: String = origin) = CloudApi(server, httpClient) { session()?.get("token")?.jsonPrimitive?.content }
    internal suspend fun start(server: String, name: String): CloudPairing {
        require(session() == null) { "Disconnect this account before linking another" }
        val url = validatedCloudOrigin(server).toString()
        config.edit { putString("server", url) }
        return api(url).start(name)
    }
    internal suspend fun poll(pair: CloudPairing): String {
        val result = api().poll(pair)
        val status = result.getValue("status").jsonPrimitive.content
        if (status == "approved") {
            val user = result.getValue("user").jsonObject
            val value = buildJsonObject {
                put("token", result.getValue("token"))
                put("deviceId", result.getValue("deviceId"))
                put("userId", user.getValue("id"))
                put("email", user.getValue("email"))
                put("managed", JsonArray(emptyList()))
                put("revision", -1)
                put("baseline", "")
            }
            withContext(Dispatchers.IO) { writeSession(value) }
        }
        return status
    }
    internal suspend fun cancel(pair: CloudPairing) = api().cancel(pair)
    private fun readSecret(id: SourceProfileId): ByteArray? = when (val result = vault.read(id)) {
        is CredentialReadResult.Present -> result.use { it.secret.copyBytes() }
        else -> null
    }
    private fun writeSession(value: JsonObject) {
        val bytes = value.toString().toByteArray()
        try {
            vault.write(ACCOUNT_VAULT_ID, bytes)
        } finally {
            bytes.fill(0)
        }
    }
    private fun withBaseline(session: JsonObject, revision: Long, snapshot: JsonObject, ids: Collection<String>) = JsonObject(
        session + mapOf(
            "revision" to JsonPrimitive(revision),
            "baseline" to JsonPrimitive(fingerprint(snapshot)),
            "managed" to JsonArray(ids.sorted().map(::JsonPrimitive)),
            "accountPhotosOnly" to snapshot.getValue("settings").jsonObject.getValue("accountPhotosOnly"),
        ),
    )
    private fun fingerprint(snapshot: JsonObject) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray()))
    private suspend fun snapshot(extraIds: Collection<SourceProfileId> = emptyList(), photosOnly: Boolean? = null): JsonObject {
        val roots = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)
        val grouped = roots.groupBy { it.profileId }
        val managed = session()?.get("managed")?.jsonArray?.map { SourceProfileId(it.jsonPrimitive.content) }.orEmpty()
        val connections = (grouped.keys + managed + extraIds).sortedBy { it.value }.mapNotNull { id ->
            val folders = grouped[id].orEmpty()
            val profile = database.sourceProfiles.get(id) ?: return@mapNotNull null
            val kind = (profile.kind as? SourceProfileKind.Known)?.value ?: return@mapNotNull null
            val config: JsonObject
            val provider: String
            val smb = database.smbConnections.get(id)
            val generic = if (smb == null && kind !in listOf(SourceKind.GOOGLE_DRIVE, SourceKind.GOOGLE_PHOTOS)) connectorStore.read(id) else null
            if (smb != null) {
                val secret = readSecret(id) ?: throw IllegalStateException("Reconnect your NAS before saving")
                try {
                    config = buildJsonObject {
                        put("host", smb.host)
                        put("port", smb.port)
                        put("share", smb.share)
                        put("domain", smb.domain)
                        put("username", smb.username)
                        put("secret", secret.toString(Charsets.UTF_8))
                    }
                } finally {
                    secret.fill(0)
                }
                provider = "SMB"
            } else if (generic != null) {
                config = buildJsonObject {
                    put("endpoint", generic.endpoint.toString())
                    put("username", generic.username)
                    put("secret", generic.secret)
                }
                provider = generic.provider.name
            } else if (kind in listOf(SourceKind.GOOGLE_DRIVE, SourceKind.GOOGLE_PHOTOS)) {
                val google = database.googleConnections.get(id) ?: return@mapNotNull null
                val secret = readSecret(id) ?: throw IllegalStateException("Reconnect Google before saving")
                try {
                    config = buildJsonObject {
                        put("vault", Base64.getEncoder().encodeToString(secret))
                        put("resourceId", google.resourceId)
                        put("settingsUri", google.settingsUri)
                        put("clientId", google.clientId)
                    }
                } finally {
                    secret.fill(0)
                }
                provider = kind.name
            } else {
                return@mapNotNull null // Local URI grants and DLNA discoveries belong to this device.
            }
            buildJsonObject {
                put("id", id.value)
                put("provider", provider)
                put("name", profile.displayName)
                put("configuration", config)
                put(
                    "roots",
                    JsonArray(
                        folders.sortedBy { it.objectId.value }.map { root ->
                            buildJsonObject {
                                put("objectId", root.objectId.value)
                                put("name", root.displayLabel)
                                put("includeDescendants", root.includeDescendants)
                                put("includedFilterIds", JsonArray(root.fileTypeFilters.sorted().map(::JsonPrimitive)))
                            }
                        },
                    ),
                )
            }
        }
        return buildJsonObject {
            put("settings", JsonObject(preferences.preferences.first().cloudJson() + mapOf("accountPhotosOnly" to JsonPrimitive(photosOnly ?: session()?.get("accountPhotosOnly")?.jsonPrimitive?.boolean ?: false))))
            put("connections", JsonArray(connections))
        }
    }

    /** Explicit restore also establishes the baseline for subsequent automatic synchronization. */
    suspend fun restore(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val session = checkNotNull(session()) { "Link your account first" }
            val remote = api().state()
            import(remote, session)
            "Account settings and connections restored"
        }
    }
    suspend fun save(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val session = checkNotNull(session()) { "Link your account first" }
            val revision = session["revision"]?.jsonPrimitive?.long ?: -1
            require(revision >= 0) { "Restore your account once before saving this TV" }
            val local = snapshot()
            api().save(revision, local.getValue("settings").jsonObject, local.getValue("connections").jsonArray)
            writeSession(withBaseline(session, revision + 1, local, local.getValue("connections").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }))
            "TV settings and connections saved to your account"
        }
    }
    suspend fun initializeFromTv(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val session = checkNotNull(session()) { "Link your account first" }
            val remote = api().state()
            require(remote.getValue("settings").jsonObject.isEmpty() && remote.getValue("connections").jsonArray.isEmpty()) { "This account already has a saved setup. Restore it first" }
            val local = snapshot()
            val revision = remote.getValue("revision").jsonPrimitive.long
            api().save(revision, local.getValue("settings").jsonObject, local.getValue("connections").jsonArray)
            writeSession(withBaseline(session, revision + 1, local, local.getValue("connections").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }))
            "This TV's setup is now saved to your account"
        }
    }

    /** Poll while the app is foregrounded. CAS failures leave local edits intact for explicit review. */
    suspend fun sync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val session = session() ?: return@withContext false
            val revision = session["revision"]?.jsonPrimitive?.long ?: -1
            if (revision < 0) {
                import(api().state(), session)
                return@withContext true
            }
            val local = snapshot()
            val changed = fingerprint(local) != session["baseline"]?.jsonPrimitive?.content
            if (changed) {
                api().save(revision, local.getValue("settings").jsonObject, local.getValue("connections").jsonArray)
                writeSession(withBaseline(session, revision + 1, local, local.getValue("connections").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }))
                false
            } else {
                val remote = api().state()
                if (remote.getValue("revision").jsonPrimitive.long == revision) {
                    false
                } else {
                    import(remote, session)
                    true
                }
            }
        }
    }
    private suspend fun import(remote: JsonObject, session: JsonObject) {
        val settings = remote.getValue("settings").jsonObject.cloudPreferences()
        val connections = remote.getValue("connections").jsonArray.map(JsonElement::jsonObject)
        require(connections.size <= 64)
        val ids = connections.map { SourceProfileId(it.getValue("id").jsonPrimitive.content) }
        require(ids.distinct().size == ids.size && ACCOUNT_VAULT_ID !in ids)
        // Validate the entire payload before mutating the vault or database.
        val configs = connections.map { value ->
            val provider = value.getValue("provider").jsonPrimitive.content
            val config = value.getValue("configuration").jsonObject
            when (provider) {
                "SMB" -> {
                    com.jedon.kellikanvas.source.smb.SmbProfile(ids[connections.indexOf(value)], config.text("host"), config.getValue("port").jsonPrimitive.int, config.text("share"), config.text("domain"), config.text("username"))
                    null
                }
                "GOOGLE_DRIVE", "GOOGLE_PHOTOS" -> {
                    Base64.getDecoder().decode(config.text("vault")).fill(0)
                    null
                }
                else -> ConnectorConfiguration(PhotoConnector.valueOf(provider), config.text("endpoint"), config.text("username"), config.text("secret"))
            }
        }
        val previousManaged = session["managed"]?.jsonArray?.map { SourceProfileId(it.jsonPrimitive.content) }.orEmpty().toSet()
        val allTouched = previousManaged + ids
        val originalSecrets = allTouched.associateWith(::readSecret)
        try {
            connections.forEachIndexed { index, value ->
                val config = value.getValue("configuration").jsonObject
                val bytes = when (value.getValue("provider").jsonPrimitive.content) {
                    "SMB" -> config.text("secret").toByteArray()
                    "GOOGLE_DRIVE", "GOOGLE_PHOTOS" -> Base64.getDecoder().decode(config.text("vault"))
                    else -> buildJsonObject {
                        put("provider", configs[index]!!.provider.name)
                        put("endpoint", configs[index]!!.endpoint.toString())
                        put("username", configs[index]!!.username)
                        put("secret", configs[index]!!.secret)
                    }.toString().toByteArray()
                }
                try {
                    vault.write(ids[index], bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            database.withTransaction {
                val retained = if (remote.getValue("settings").jsonObject["accountPhotosOnly"]?.jsonPrimitive?.boolean == true) emptyList() else database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID).filter { it.profileId !in allTouched }
                if (database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID) == null) database.collections.upsert(CatalogCollection(CatalogIds.DEFAULT_COLLECTION_ID, "Your photos"))
                val roots = mutableListOf<SelectedRoot>()
                connections.forEachIndexed { index, value ->
                    val id = ids[index]
                    val provider = value.getValue("provider").jsonPrimitive.content
                    val name = value.text("name")
                    val config = value.getValue("configuration").jsonObject
                    val kind = when (provider) {
                        "SMB" -> SourceKind.SMB
                        "GOOGLE_DRIVE" -> SourceKind.GOOGLE_DRIVE
                        "GOOGLE_PHOTOS" -> SourceKind.GOOGLE_PHOTOS
                        else -> configs[index]!!.provider.kind
                    }
                    database.sourceProfiles.upsert(SourceProfile(id, kind, name, SourceProfileStatus.AVAILABLE, createdAtMillis = database.sourceProfiles.get(id)?.createdAtMillis ?: System.currentTimeMillis()))
                    if (provider == "SMB") database.smbConnections.upsert(SmbConnection(id, config.text("host"), config.getValue("port").jsonPrimitive.int, config.text("share"), config.text("domain"), config.text("username"), name))
                    if (provider.startsWith("GOOGLE_")) database.googleConnections.upsert(GoogleConnection(id, config.text("resourceId"), config.text("settingsUri"), config.text("clientId")))
                    value.getValue("roots").jsonArray.forEach { element ->
                        val root = element.jsonObject
                        roots += SelectedRoot(
                            CatalogIds.DEFAULT_COLLECTION_ID,
                            id,
                            ProviderObjectId(root.text("objectId")),
                            root.text("name"),
                            root.getValue("includeDescendants").jsonPrimitive.boolean,
                            root["includedFilterIds"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                        )
                    }
                }
                database.selectedRoots.replaceAllForCollection(CatalogIds.DEFAULT_COLLECTION_ID, retained + roots)
                (previousManaged - ids.toSet()).forEach { database.sourceProfiles.delete(it) }
            }
        } catch (failure: Exception) {
            originalSecrets.forEach { (id, bytes) -> if (bytes == null) vault.remove(id) else vault.write(id, bytes) }
            throw failure
        } finally {
            originalSecrets.values.forEach { it?.fill(0) }
        }
        (previousManaged - ids.toSet()).forEach { vault.remove(it) }
        preferences.update { it.copy(appPreferences = settings.appPreferences, reducedMotion = settings.reducedMotion) }
        writeSession(withBaseline(session, remote.getValue("revision").jsonPrimitive.long, snapshot(ids, remote.getValue("settings").jsonObject["accountPhotosOnly"]?.jsonPrimitive?.boolean ?: false), ids.map { it.value }))
    }
    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val session = session() ?: return@withContext
            try {
                api().logout()
            } catch (_: SourceFailure.AuthenticationRequired) { /* Already revoked/expired. */ }
            val managed = session["managed"]?.jsonArray?.map { SourceProfileId(it.jsonPrimitive.content) }.orEmpty()
            database.withTransaction {
                val roots = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID).filter { it.profileId !in managed }
                database.selectedRoots.replaceAllForCollection(CatalogIds.DEFAULT_COLLECTION_ID, roots)
                managed.forEach { database.sourceProfiles.delete(it) }
            }
            managed.forEach { vault.remove(it) }
            vault.remove(ACCOUNT_VAULT_ID)
        }
    }
}
private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

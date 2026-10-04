package com.jedon.kellikanvas.account

import android.content.Context
import android.net.ConnectivityManager
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.connected.ConnectorConfiguration
import com.jedon.kellikanvas.source.connected.ConnectorHttp
import com.jedon.kellikanvas.source.connected.PhotoConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Uses Immich's supported session and API-key endpoints; passwords never become saved connections. */
internal class ImmichIntegration(private val client: OkHttpClient, private val discovery: suspend (List<String>) -> List<String>) {
    suspend fun execute(work: JsonObject): JsonObject = try {
        withTimeoutOrNull(60_000) {
            val operation = work.text("operation")
            if (operation == "discover") {
                return@withTimeoutOrNull buildJsonObject {
                    put(
                        "servers",
                        JsonArray(
                            discovery(work.getValue("candidates").jsonArray.map { it.jsonPrimitive.content }).take(32).map { endpoint ->
                                buildJsonObject {
                                    put("endpoint", endpoint)
                                    put("name", "Immich · ${endpoint.toHttpUrlOrNull()?.host}")
                                }
                            },
                        ),
                    )
                }
            }
            require(operation in setOf("login", "key"))
            val configuration = ConnectorConfiguration(PhotoConnector.IMMICH, work.text("endpoint"), work.text("username"), work.text("secret"))
            val origin = configuration.endpoint
            val api = if (origin.pathSegments.filter(String::isNotEmpty).lastOrNull() == "api") origin else origin.newBuilder().addPathSegment("api").addPathSegment("").build()
            fun url(path: String) = requireNotNull(api.resolve(path))
            val id = SourceProfileId("immich-integration")
            var key = configuration.secret
            if (operation == "login") {
                val public = ConnectorHttp(id, client, { emptyMap() }, origin)
                val session = public.json(
                    url("auth/login"),
                    "POST",
                    buildJsonObject {
                        put("email", configuration.username)
                        put("password", configuration.secret)
                    }.body(),
                ).jsonObject
                val token = session.text("accessToken")
                require(token.isNotBlank())
                val authenticated = ConnectorHttp(id, client, { mapOf("Authorization" to "Bearer $token") }, origin)
                try {
                    if (session["shouldChangePassword"]?.jsonPrimitive?.booleanOrNull == true) return@withTimeoutOrNull failure("password_change")
                    val response = authenticated.json(
                        url("api-keys"),
                        "POST",
                        buildJsonObject {
                            put("name", "KelliKanvas ${work.text("id")}")
                            put("permissions", JsonArray(listOf("album.read", "asset.read", "asset.download", "asset.view").map(::JsonPrimitive)))
                        }.body(),
                    ).jsonObject
                    key = response.text("secret")
                    require(key.isNotBlank())
                } finally {
                    // Invalidate the temporary password-login session. Keep only the restricted integration key.
                    try {
                        authenticated.json(url("auth/logout"), "POST", buildJsonObject {}.body())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { }
                }
            } else {
                ConnectorHttp(id, client, { mapOf("x-api-key" to key) }, origin).json(url("albums"))
            }
            buildJsonObject {
                put("servers", JsonArray(emptyList()))
                put("secret", key)
            }
        } ?: failure("network")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: com.jedon.kellikanvas.model.SourceFailure.AuthenticationRequired) {
        failure("authentication")
    } catch (_: com.jedon.kellikanvas.model.SourceFailure.PermissionRevoked) {
        failure("authentication")
    } catch (_: Exception) {
        failure("unavailable")
    }
    private fun failure(error: String) = buildJsonObject {
        put("servers", JsonArray(emptyList()))
        put("error", error)
    }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty().takeUnless { it == "null" }.orEmpty()
}

/** Search only common local hosts and one /24 of the TV's active private IPv4 network. */
internal class LocalImmichDiscovery(private val context: Context, client: OkHttpClient) {
    private val probeClient = client.newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()
    suspend fun discover(saved: List<String>): List<String> = coroutineScope {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val addresses = connectivity?.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
        val candidates = (saved + listOf("darklingnas", "darklingnas.local", "immich", "immich.local") + addresses.take(1).flatMap { subnetCandidates(it.hostAddress.orEmpty()) })
            .mapNotNull { value -> (if (value.contains("://")) value else "http://$value:2283/").toHttpUrlOrNull() }.distinct().take(320)
        val semaphore = Semaphore(16)
        candidates.map { origin ->
            async {
                semaphore.withPermit {
                    try {
                        // Refuse redirects; discovery sends no credentials, even to saved endpoints.
                        val open = withContext(Dispatchers.IO) {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(origin.host, origin.port), 200)
                                true
                            }
                        }
                        if (!open) return@withPermit null
                        val api = if (origin.pathSegments.filter(String::isNotEmpty).lastOrNull() == "api") origin else origin.newBuilder().addPathSegment("api").addPathSegment("").build()
                        val response = ConnectorHttp(SourceProfileId("immich-discovery"), probeClient, { emptyMap() }, origin).json(requireNotNull(api.resolve("server/ping"))).jsonObject
                        if (response["res"]?.jsonPrimitive?.content == "pong") origin.toString() else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().distinctBy { origin -> origin.toHttpUrlOrNull()?.let { "${it.host}:${it.port}${it.encodedPath}" } }.take(32)
    }
    companion object {
        internal fun subnetCandidates(address: String): List<String> {
            val octets = address.split('.').map { it.toIntOrNull() ?: return emptyList() }
            if (octets.size != 4 || octets.any { it !in 0..255 } || !(octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31)) return emptyList()
            val prefix = octets.take(3).joinToString(".")
            return (1..254).map { "$prefix.$it" }
        }
    }
}

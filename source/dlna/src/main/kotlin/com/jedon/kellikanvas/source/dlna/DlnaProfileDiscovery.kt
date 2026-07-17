package com.jedon.kellikanvas.source.dlna

import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.URI

class DlnaProfileDiscovery(
    private val discover: suspend () -> List<SsdpDevice>,
    private val loadDescription: suspend (URI) -> ByteArray,
    private val parser: DeviceDescriptionParser = DeviceDescriptionParser(),
) {
    constructor(
        discoverer: SsdpDiscoverer,
        httpClient: OkHttpClient,
    ) : this(
        discover = discoverer::discover,
        loadDescription = DeviceDescriptionClient(httpClient)::load,
    )

    suspend fun setup(profileId: SourceProfileId): List<DlnaProfile> = discover()
        .mapNotNull { device -> resolve(device, profileId) }

    suspend fun repair(profile: DlnaProfile): DlnaProfile {
        discover()
            .filter { it.udn.equals(profile.serverUdn, ignoreCase = true) }
            .forEach { device ->
                resolve(device, profile.id)?.let { return it }
            }
        throw DlnaSourceUnavailableException()
    }

    private suspend fun resolve(
        device: SsdpDevice,
        profileId: SourceProfileId,
    ): DlnaProfile? = try {
        val description = parser.parse(loadDescription(device.location), device.location.toString())
        if (!description.udn.equals(device.udn, ignoreCase = true)) return null
        DlnaEndpointPolicy(device.location).validateInitial(description.controlUrl)
        DlnaProfile(
            id = profileId,
            serverUdn = device.udn,
            descriptionLocation = device.location,
            controlUrl = description.controlUrl,
            contentDirectoryVersion = description.version,
        )
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: DlnaProtocolException) {
        null
    } catch (_: DlnaSecurityException) {
        null
    } catch (_: IOException) {
        null
    }
}

class DeviceDescriptionClient(
    httpClient: OkHttpClient,
) {
    private val baseClient =
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .addNetworkInterceptor(NO_CREDENTIALS_INTERCEPTOR)
            .build()

    suspend fun load(discoveredLocation: URI): ByteArray {
        val policy = DlnaEndpointPolicy(discoveredLocation)
        val client = baseClient.newBuilder().dns(policy::pinnedAddresses).build()
        var current = discoveredLocation
        var redirects = 0
        while (true) {
            policy.validateInitial(current)
            val call = client.newCall(Request.Builder().url(current.toURL()).get().build())
            val response = call.awaitResponse()
            if (response.code in REDIRECT_CODES) {
                val location =
                    response.header("Location")
                        ?: run {
                            response.close()
                            throw DlnaProtocolException("Description redirect location missing")
                        }
                val next = current.resolve(location)
                response.close()
                redirects++
                policy.validateRedirect(current, next, redirects)
                current = next
                continue
            }
            response.use {
                if (!it.isSuccessful) throw DlnaProtocolException("Description HTTP ${it.code}")
                return call.readBoundedCancellable(it.body.source(), DESCRIPTION_MAX_BYTES, "Description")
            }
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

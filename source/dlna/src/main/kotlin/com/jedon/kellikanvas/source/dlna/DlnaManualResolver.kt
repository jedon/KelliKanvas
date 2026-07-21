package com.jedon.kellikanvas.source.dlna

import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.net.URI

class DlnaManualResolver(
    private val loadDescription: suspend (URI) -> ByteArray,
    private val parser: DeviceDescriptionParser = DeviceDescriptionParser(),
    private val profileIdFactory: (String) -> SourceProfileId = ::stableDlnaProfileId,
) {
    constructor(httpClient: OkHttpClient) : this(DeviceDescriptionClient(httpClient)::load)

    suspend fun resolve(input: String): DlnaProfile {
        var lastFailure: Throwable? = null
        for (candidate in descriptionCandidates(input)) {
            try {
                val location = URI(candidate)
                val description = parser.parse(loadDescription(location), location.toString())
                DlnaEndpointPolicy(location).validateInitial(description.controlUrl)
                return DlnaProfile(
                    id = profileIdFactory(description.udn),
                    serverUdn = description.udn,
                    descriptionLocation = location,
                    controlUrl = description.controlUrl,
                    contentDirectoryVersion = description.version,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw DlnaSourceUnavailableException(lastFailure)
    }

    /**
     * Tries [preferredHosts] (e.g. resolver-provided) first, then each
     * [BUILT_IN_HOST_CANDIDATES] entry via [resolve] until one succeeds.
     * Returns the matched host input along with the profile.
     */
    suspend fun resolveBuiltIn(preferredHosts: List<String> = emptyList()): BuiltInResolveResult {
        var lastFailure: Throwable? = null
        for (host in (preferredHosts + BUILT_IN_HOST_CANDIDATES).distinct()) {
            try {
                return BuiltInResolveResult(matchedHost = host, profile = resolve(host))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw DlnaSourceUnavailableException(lastFailure)
    }

    companion object {
        /**
         * Household QNAP hosts to try when SSDP discovery fails.
         * Ordered with the probe-proven description URL first to avoid slow port-80 timeouts.
         */
        val BUILT_IN_HOST_CANDIDATES: List<String> =
            listOf(
                "http://192.168.68.62:8200/rootDesc.xml",
                "192.168.68.62:8200",
                "192.168.68.62",
                "darklingnas",
                "darklingnas.local",
                "DarklingNAS",
            )

        fun builtInDescriptionCandidates(): List<String> = BUILT_IN_HOST_CANDIDATES
            .flatMap(::descriptionCandidates)
            .distinct()

        fun descriptionCandidates(input: String): List<String> {
            val trimmed = input.trim()
            if (trimmed.contains("://")) {
                return listOf(trimmed)
            }
            val hostPort = trimmed
            val candidates = mutableListOf<String>()
            if (!hostPort.contains(':')) {
                candidates += "http://$hostPort:8200/rootDesc.xml"
            }
            candidates += "http://$hostPort/rootDesc.xml"
            candidates += "http://$hostPort/description.xml"
            return candidates
        }
    }
}

data class BuiltInResolveResult(
    val matchedHost: String,
    val profile: DlnaProfile,
)

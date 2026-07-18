package com.jedon.kellikanvas.source.dlna

import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.IOException
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
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw DlnaSourceUnavailableException(lastFailure)
    }

    companion object {
        fun descriptionCandidates(input: String): List<String> {
            val trimmed = input.trim()
            if (trimmed.contains("://")) {
                return listOf(trimmed)
            }
            val hostPort = trimmed
            val candidates =
                mutableListOf(
                    "http://$hostPort/rootDesc.xml",
                    "http://$hostPort/description.xml",
                )
            if (!hostPort.contains(':')) {
                candidates += "http://$hostPort:8200/rootDesc.xml"
            }
            return candidates
        }
    }
}

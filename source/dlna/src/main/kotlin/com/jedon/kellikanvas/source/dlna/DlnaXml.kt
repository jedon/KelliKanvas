package com.jedon.kellikanvas.source.dlna

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.net.URI

internal const val DESCRIPTION_MAX_BYTES = 256 * 1024
internal const val SOAP_DIDL_MAX_BYTES = 2 * 1024 * 1024
internal const val XML_MAX_DEPTH = 32
internal const val XML_MAX_TEXT_OR_ATTRIBUTE = 4 * 1024
internal const val DIDL_MAX_OBJECTS = 500

open class DlnaProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class DlnaIndexBeyondRangeException : DlnaProtocolException("ContentDirectory index beyond available entries")

data class DlnaDeviceDescription(
    val udn: String,
    val friendlyName: String,
    val controlUrl: URI,
    val version: Int,
)

data class DlnaResource(
    val uri: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val byteLength: Long?,
)

data class DlnaObject(
    val serverUdn: String,
    val objectId: String,
    val parentId: String?,
    val title: String,
    val isContainer: Boolean,
    val resources: List<DlnaResource>,
) {
    val stableId: String = "$serverUdn\u0000$objectId"
}

data class DlnaBrowsePage(
    val objects: List<DlnaObject>,
    val numberReturned: Int,
    val totalMatches: Int,
)

class DeviceDescriptionParser {
    fun parse(
        bytes: ByteArray,
        descriptionUrl: String,
    ): DlnaDeviceDescription {
        val parser = secureParser(bytes, DESCRIPTION_MAX_BYTES)
        var urlBase: String? = null
        val deviceStack = mutableListOf<MutableDevice>()
        val candidates = mutableListOf<MutableDevice>()
        walk(parser) { event, xml ->
            if (event == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "URLBase" -> if (deviceStack.isEmpty()) urlBase = boundedNextText(xml)
                    "device" -> deviceStack += MutableDevice(nestingLevel = deviceStack.size)
                    "service" -> deviceStack.lastOrNull()?.currentService = MutableService()
                    "friendlyName" -> deviceStack.lastOrNull()?.friendlyName = boundedNextText(xml)
                    "UDN" -> deviceStack.lastOrNull()?.udn = boundedNextText(xml)
                    "deviceType" -> deviceStack.lastOrNull()?.deviceType = boundedNextText(xml)
                    "serviceType" -> deviceStack.lastOrNull()?.currentService?.type = boundedNextText(xml)
                    "controlURL" -> deviceStack.lastOrNull()?.currentService?.controlUrl = boundedNextText(xml)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (xml.name) {
                    "service" -> {
                        deviceStack.lastOrNull()?.let { device ->
                            device.currentService?.let(device.services::add)
                            device.currentService = null
                        }
                    }
                    "device" -> if (deviceStack.isNotEmpty()) candidates += deviceStack.removeAt(deviceStack.lastIndex)
                }
            }
        }
        val device =
            candidates
                .filter {
                    MEDIA_SERVER_VERSION.find(it.deviceType.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { version ->
                        version >= 1
                    } == true
                }.minByOrNull(MutableDevice::nestingLevel)
                ?: throw DlnaProtocolException("Description is not a MediaServer")
        val selected =
            device.services
                .mapNotNull { service ->
                    CONTENT_DIRECTORY_VERSION.find(service.type.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { version ->
                        if (version in 1..2 && !service.controlUrl.isNullOrBlank()) {
                            version to service.controlUrl!!
                        } else {
                            null
                        }
                    }
                }.maxByOrNull(Pair<Int, String>::first)
                ?: throw DlnaProtocolException("ContentDirectory service missing")
        val descriptionBase = validatedHttpUri(descriptionUrl, "description URL")
        val base = urlBase?.let { validatedHttpUri(it, "URLBase") } ?: descriptionBase
        return DlnaDeviceDescription(
            udn = device.udn?.takeIf(String::isNotBlank) ?: throw DlnaProtocolException("UDN missing"),
            friendlyName = device.friendlyName?.takeIf(String::isNotBlank) ?: "Media server",
            controlUrl = validatedHttpUri(base.resolve(selected.second).toString(), "control URL"),
            version = selected.first,
        )
    }

    private data class MutableDevice(
        val nestingLevel: Int,
        var friendlyName: String? = null,
        var udn: String? = null,
        var deviceType: String? = null,
        val services: MutableList<MutableService> = mutableListOf(),
        var currentService: MutableService? = null,
    )

    private data class MutableService(
        var type: String? = null,
        var controlUrl: String? = null,
    )

    private companion object {
        val MEDIA_SERVER_VERSION = Regex("""(?i)MediaServer:(\d+)$""")
        val CONTENT_DIRECTORY_VERSION = Regex("""(?i)ContentDirectory:(\d+)$""")
    }
}

class DidlLiteParser {
    fun parse(
        bytes: ByteArray,
        serverUdn: String,
    ): List<DlnaObject> {
        val parser = secureParser(bytes, SOAP_DIDL_MAX_BYTES)
        val result = mutableListOf<DlnaObject>()
        var current: MutableDlnaObject? = null
        walk(parser) { event, xml ->
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (xml.name == "item" || xml.name == "container") {
                        if (result.size >= DIDL_MAX_OBJECTS) throw DlnaProtocolException("Too many DIDL objects")
                        current =
                            MutableDlnaObject(
                                objectId = requiredAttribute(xml, "id"),
                                parentId = optionalAttribute(xml, "parentID"),
                                isContainer = xml.name == "container",
                            )
                    } else if (current != null && xml.name == "title") {
                        current!!.title = boundedNextText(xml)
                    } else if (current != null && xml.name == "res") {
                        val protocolInfo = optionalAttribute(xml, "protocolInfo")
                        val mime = protocolInfo?.split(';')?.firstOrNull()?.split(':')?.getOrNull(2)
                        val resolution = optionalAttribute(xml, "resolution")?.split('x')
                        val byteLength = optionalAttribute(xml, "size")?.toLongOrNull()
                        val uri = boundedNextText(xml).trim()
                        current!!.resources +=
                            DlnaResource(
                                uri = uri,
                                mimeType = mime,
                                width = resolution?.getOrNull(0)?.toIntOrNull(),
                                height = resolution?.getOrNull(1)?.toIntOrNull(),
                                byteLength = byteLength,
                            )
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (xml.name == "item" || xml.name == "container") {
                        val objectValue = current ?: throw DlnaProtocolException("Malformed DIDL object")
                        result +=
                            DlnaObject(
                                serverUdn = serverUdn,
                                objectId = objectValue.objectId,
                                parentId = objectValue.parentId,
                                title = objectValue.title.takeIf(String::isNotBlank) ?: "Untitled",
                                isContainer = objectValue.isContainer,
                                resources = objectValue.resources.toList(),
                            )
                        current = null
                    }
                }
            }
        }
        return result
    }

    private data class MutableDlnaObject(
        val objectId: String,
        val parentId: String?,
        val isContainer: Boolean,
        var title: String = "",
        val resources: MutableList<DlnaResource> = mutableListOf(),
    )
}

class DlnaResourceSelector(
    private val supportedMimeTypes: Set<String>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) {
    fun select(resources: List<DlnaResource>): DlnaResource? = resources
        .asSequence()
        .filter { it.mimeType?.lowercase() in supportedMimeTypes.map(String::lowercase) }
        .filter { it.width == null || it.width > 0 }
        .filter { it.height == null || it.height > 0 }
        .filter { it.byteLength == null || it.byteLength >= 0 }
        .filter { runCatching { URI(it.uri).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false) }
        .minWithOrNull(
            compareBy<DlnaResource>(
                { resolutionDistance(it) },
                { it.byteLength ?: Long.MAX_VALUE },
                { it.uri },
            ),
        )

    private fun resolutionDistance(resource: DlnaResource): Long {
        val width = resource.width ?: return Long.MAX_VALUE / 2
        val height = resource.height ?: return Long.MAX_VALUE / 2
        return kotlin.math.abs(width.toLong() - targetWidth) +
            kotlin.math.abs(height.toLong() - targetHeight)
    }
}

class ContentDirectoryClient(
    httpClient: OkHttpClient,
    private val controlUrl: URI,
    private val serverUdn: String,
    version: Int,
    endpointPolicy: DlnaEndpointPolicy? = null,
) {
    private val httpClient =
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .addNetworkInterceptor(NO_CREDENTIALS_INTERCEPTOR)
            .apply {
                endpointPolicy?.let { dns(it::pinnedAddresses) }
            }.build()
    private val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:$version"

    suspend fun browseDirectChildren(
        objectId: String,
        startingIndex: Int,
        requestedCount: Int,
    ): DlnaBrowsePage = browse(objectId, "BrowseDirectChildren", startingIndex, requestedCount)

    suspend fun browseMetadata(objectId: String): DlnaObject = browse(
        objectId,
        "BrowseMetadata",
        0,
        0,
    ).objects.singleOrNull() ?: throw DlnaObjectMissingException()

    private suspend fun browse(
        objectId: String,
        browseFlag: String,
        startingIndex: Int,
        requestedCount: Int,
    ): DlnaBrowsePage {
        require(startingIndex >= 0)
        require(requestedCount in 0..DIDL_MAX_OBJECTS)
        require(browseFlag == "BrowseMetadata" || requestedCount > 0)
        val envelope =
            """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:Browse xmlns:u="$serviceType">
                <ObjectID>${xmlEscape(objectId)}</ObjectID><BrowseFlag>$browseFlag</BrowseFlag>
                <Filter>*</Filter><StartingIndex>$startingIndex</StartingIndex>
                <RequestedCount>$requestedCount</RequestedCount><SortCriteria></SortCriteria>
              </u:Browse></s:Body>
            </s:Envelope>
            """.trimIndent()
        val request =
            Request.Builder()
                .url(controlUrl.toURL())
                .header("SOAPAction", "\"$serviceType#Browse\"")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()
        val call = httpClient.newCall(request)
        return call.awaitResponse().use { response ->
            val bytes = call.readBoundedCancellable(response.body.source(), SOAP_DIDL_MAX_BYTES, "SOAP")
            if (bytes.size > SOAP_DIDL_MAX_BYTES) throw DlnaProtocolException("SOAP body too large")
            if (!response.isSuccessful) {
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    throw DlnaProtocolException("ContentDirectory redirect rejected")
                }
                when (parseUpnpErrorCode(bytes)) {
                    701, 714 -> throw DlnaObjectMissingException()
                    720 -> throw DlnaIndexBeyondRangeException()
                }
                throw DlnaProtocolException("ContentDirectory HTTP ${response.code}")
            }
            val soap = parseSoapBrowse(bytes)
            val page =
                DlnaBrowsePage(
                    objects = DidlLiteParser().parse(soap.result.encodeToByteArray(), serverUdn),
                    numberReturned = soap.numberReturned,
                    totalMatches = soap.totalMatches,
                )
            page.validateForRequest(startingIndex, if (browseFlag == "BrowseMetadata") 1 else requestedCount)
            page
        }
    }

    private fun parseSoapBrowse(bytes: ByteArray): SoapBrowse {
        val parser = secureParser(bytes, SOAP_DIDL_MAX_BYTES)
        var result: String? = null
        var returned: Int? = null
        var total: Int? = null
        walk(parser) { event, xml ->
            if (event == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "Result" -> result = nextText(xml, SOAP_DIDL_MAX_BYTES)
                    "NumberReturned" -> returned = boundedNextText(xml).toIntOrNull()
                    "TotalMatches" -> total = boundedNextText(xml).toIntOrNull()
                }
            }
        }
        return SoapBrowse(
            result ?: throw DlnaProtocolException("SOAP Result missing"),
            returned ?: throw DlnaProtocolException("SOAP NumberReturned missing"),
            total ?: throw DlnaProtocolException("SOAP TotalMatches missing"),
        )
    }

    private fun parseUpnpErrorCode(bytes: ByteArray): Int? {
        val parser = secureParser(bytes, SOAP_DIDL_MAX_BYTES)
        var errorCode: Int? = null
        var faultDepth: Int? = null
        var upnpErrorDepth: Int? = null
        walk(parser) { event, xml ->
            when (event) {
                XmlPullParser.START_TAG ->
                    when (xml.name) {
                        "Fault" -> faultDepth = xml.depth
                        "UPnPError" -> if (faultDepth != null) upnpErrorDepth = xml.depth
                        "errorCode" -> if (upnpErrorDepth != null) {
                            errorCode = boundedNextText(xml).trim().toIntOrNull()
                        }
                    }
                XmlPullParser.END_TAG ->
                    when (xml.name) {
                        "UPnPError" -> upnpErrorDepth = null
                        "Fault" -> faultDepth = null
                    }
            }
        }
        return errorCode
    }

    private data class SoapBrowse(val result: String, val numberReturned: Int, val totalMatches: Int)
}

private fun secureParser(
    bytes: ByteArray,
    maxBytes: Int,
): XmlPullParser {
    if (bytes.size > maxBytes) throw DlnaProtocolException("XML body too large")
    val text = bytes.decodeToString()
    if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
        throw DlnaProtocolException("DOCTYPE and entities are forbidden")
    }
    return try {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
    } catch (failure: Exception) {
        throw DlnaProtocolException("Invalid XML", failure)
    }
}

private inline fun walk(
    parser: XmlPullParser,
    crossinline consume: (Int, XmlPullParser) -> Unit,
) {
    try {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (parser.depth > XML_MAX_DEPTH) throw DlnaProtocolException("XML nesting too deep")
            if (event == XmlPullParser.START_TAG) {
                for (index in 0 until parser.attributeCount) {
                    if (parser.getAttributeValue(index).length > XML_MAX_TEXT_OR_ATTRIBUTE) {
                        throw DlnaProtocolException("XML attribute too large")
                    }
                }
            } else if (event == XmlPullParser.TEXT && parser.text.length > XML_MAX_TEXT_OR_ATTRIBUTE) {
                throw DlnaProtocolException("XML text too large")
            }
            consume(event, parser)
            event = parser.next()
        }
    } catch (failure: DlnaProtocolException) {
        throw failure
    } catch (failure: Exception) {
        throw DlnaProtocolException("Malformed XML", failure)
    }
}

private fun boundedNextText(parser: XmlPullParser): String = nextText(parser, XML_MAX_TEXT_OR_ATTRIBUTE)

private fun nextText(
    parser: XmlPullParser,
    maxLength: Int,
): String {
    val value = parser.nextText()
    if (value.length > maxLength) throw DlnaProtocolException("XML text too large")
    return value
}

private fun requiredAttribute(
    parser: XmlPullParser,
    name: String,
): String = optionalAttribute(parser, name) ?: throw DlnaProtocolException("Required DIDL attribute missing")

private fun optionalAttribute(
    parser: XmlPullParser,
    name: String,
): String? = parser.getAttributeValue(null, name)?.also {
    if (it.length > XML_MAX_TEXT_OR_ATTRIBUTE) throw DlnaProtocolException("XML attribute too large")
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun validatedHttpUri(
    value: String,
    label: String,
): URI {
    val uri = runCatching { URI(value) }.getOrElse {
        throw DlnaProtocolException("Invalid $label", it)
    }
    if (!uri.isAbsolute ||
        uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.host.isNullOrBlank() ||
        uri.userInfo != null
    ) {
        throw DlnaProtocolException("Invalid $label")
    }
    return uri
}

internal fun readBounded(
    source: BufferedSource,
    maxBytes: Int,
    label: String,
): ByteArray {
    val buffer = Buffer()
    while (buffer.size <= maxBytes) {
        val read = source.read(buffer, minOf(8_192L, maxBytes + 1L - buffer.size))
        if (read == -1L) return buffer.readByteArray()
    }
    throw DlnaProtocolException("$label body too large")
}

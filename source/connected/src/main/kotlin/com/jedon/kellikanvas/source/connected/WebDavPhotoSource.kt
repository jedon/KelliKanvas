package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV is shared by Nextcloud, ownCloud, Seafile and many NAS products. */
class WebDavPhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient) : ConnectedPhotoSource(id, config) {
    override val rootFolder = FolderRef(id, ProviderObjectId(config.endpoint.encodedPath))
    private val http = ConnectorHttp(id, client, { mapOf("Authorization" to Credentials.basic(config.username, config.secret), "Depth" to "1") }, config.endpoint)
    private fun location(path: String): HttpUrl {
        val url = requireNotNull(config.endpoint.resolve(path))
        require(url.scheme == config.endpoint.scheme && url.host == config.endpoint.host && url.port == config.endpoint.port && url.encodedPath.startsWith(config.endpoint.encodedPath) && url.query == null && url.fragment == null)
        require(url.pathSegments.none { it.contains('/') || it.contains('\\') || it == ".." })
        return url
    }
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val offset = continuation(folder, cursor, limit)?.toInt() ?: 0
        val target = location(folder.objectId.value)
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType())
        val text = http.text(target, "PROPFIND", body)
        // Android's bundled XML parser rejects external entities when DOCTYPE is forbidden.
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "DTD is forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        val document = builder.parse(InputSource(StringReader(text)))
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        val entries = (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index) as Element
            val url = runCatching { location(response.dav("href")) }.getOrNull() ?: return@mapNotNull null
            if (url.encodedPath.trimEnd('/') == target.encodedPath.trimEnd('/')) return@mapNotNull null
            if (url.encodedPath.trimEnd('/').substringBeforeLast('/') != target.encodedPath.trimEnd('/')) return@mapNotNull null
            val propstats = response.getElementsByTagNameNS("DAV:", "propstat")
            val props = (0 until propstats.length).map { propstats.item(it) as Element }.filter { it.dav("status").split(' ').getOrNull(1) == "200" }
            if (props.isEmpty()) return@mapNotNull null
            fun value(name: String) = props.firstNotNullOfOrNull { it.dav(name).takeIf(String::isNotEmpty) }.orEmpty()
            val name = value("displayname").ifBlank { url.pathSegments.lastOrNull(String::isNotEmpty).orEmpty() }
            if (props.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 }) {
                folder(url.encodedPath.trimEnd('/') + "/", name)
            } else {
                val mime = value("getcontenttype").substringBefore(';').ifBlank { photoMime(name) }
                if (!mime.startsWith("image/")) return@mapNotNull null
                val modified = runCatching { ZonedDateTime.parse(value("getlastmodified"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                photo(url.encodedPath, name, value("getcontentlength").toLongOrNull(), modified, mime)
            }
        }.distinctBy {
            when (it) {
                is SourceEntry.Folder -> it.ref.objectId
                is SourceEntry.Photo -> it.asset.objectId
            }
        }.sortedBy { it.name.lowercase() }
        return page(folder, limit, entries.drop(offset).take(limit), (offset + limit).takeIf { it < entries.size }?.toString())
    }
    override suspend fun openPhotoStream(asset: AssetRef) = http.open(location(asset.objectId.value))
}

private fun Element.dav(name: String): String = getElementsByTagNameNS("DAV:", name).item(0)?.textContent.orEmpty().trim()

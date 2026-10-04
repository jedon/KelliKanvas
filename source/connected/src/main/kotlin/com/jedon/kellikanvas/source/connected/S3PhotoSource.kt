package com.jedon.kellikanvas.source.connected

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

class S3PhotoSource(id: SourceProfileId, config: ConnectorConfiguration, client: OkHttpClient, now: () -> Instant = Instant::now) : ConnectedPhotoSource(id, config) {
    private val region = Regex("(?:^|\\.)s3[.-]([a-z]{2}-[a-z]+-\\d+)\\.").find(config.endpoint.host)?.groupValues?.get(1) ?: "us-east-1"
    private val http = ConnectorHttp(id, client, { emptyMap() }, config.endpoint) { url, method -> s3Signature(url, method, config.username, config.secret, region, now()) }
    override suspend fun listConnectedPage(folder: FolderRef, cursor: PageCursor?, limit: Int): com.jedon.kellikanvas.model.Page<SourceEntry> {
        val previous = continuation(folder, cursor, limit)
        val prefix = folder.objectId.value.takeUnless { it == "root" }.orEmpty()
        val url = config.endpoint.newBuilder().addEncodedQueryParameter("list-type", "2").addEncodedQueryParameter("delimiter", "%2F").addEncodedQueryParameter("prefix", awsEncode(prefix)).addEncodedQueryParameter("max-keys", limit.toString())
            .apply { previous?.let { addQueryParameter("continuation-token", awsEncode(it)) } }.build()
        val text = http.text(url)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }.parse(InputSource(StringReader(text)))
        val entries = mutableListOf<SourceEntry>()
        val directories = document.getElementsByTagNameNS("*", "CommonPrefixes")
        for (index in 0 until directories.length) {
            val key = (directories.item(index) as Element).s3("Prefix")
            if (key.startsWith(prefix) && key != prefix) entries += folder(key, key.trimEnd('/').substringAfterLast('/'))
        }
        val objects = document.getElementsByTagNameNS("*", "Contents")
        for (index in 0 until objects.length) {
            val item = objects.item(index) as Element
            val key = item.s3("Key")
            if (key.startsWith(prefix) && photoMime(key).startsWith("image/")) entries += photo(key, key.substringAfterLast('/'), item.s3("Size").toLongOrNull(), instant(item.s3("LastModified")))
        }
        val next = document.documentElement.s3("NextContinuationToken").takeIf(String::isNotBlank)
        require(next == null || next != previous) { "The server repeated a page" }
        require(document.documentElement.s3("IsTruncated") != "true" || next != null) { "The server omitted the next page" }
        return page(folder, limit, entries, next)
    }
    override suspend fun openPhotoStream(asset: AssetRef): com.jedon.kellikanvas.source.PhotoByteStream {
        val key = asset.objectId.value
        require(key.split('/').none { it == "." || it == ".." })
        return http.open(config.endpoint.newBuilder().encodedPath(config.endpoint.encodedPath + key.split('/').joinToString("/") { awsEncode(it) }).build())
    }
}

private fun Element.s3(name: String) = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()

/** AWS Signature Version 4, header authentication for read-only requests with an empty body. */
internal fun s3Signature(url: HttpUrl, method: String, accessKey: String, secretKey: String, region: String, now: Instant): Map<String, String> {
    val time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
    val date = time.take(8)
    val scope = "$date/$region/s3/aws4_request"
    val host = url.host + if (url.port == (if (url.isHttps) 443 else 80)) "" else ":${url.port}"
    val payloadHash = sha256("")
    val query = url.encodedQuery.orEmpty().split('&').filter(String::isNotEmpty).sorted().joinToString("&")
    val canonical = "$method\n${url.encodedPath}\n$query\nhost:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$time\n\nhost;x-amz-content-sha256;x-amz-date\n$payloadHash"
    val stringToSign = "AWS4-HMAC-SHA256\n$time\n$scope\n${sha256(canonical)}"
    val dateKey = hmac(("AWS4$secretKey").toByteArray(), date)
    val regionKey = hmac(dateKey, region)
    val serviceKey = hmac(regionKey, "s3")
    val signingKey = hmac(serviceKey, "aws4_request")
    val signature = hmac(signingKey, stringToSign).hex()
    listOf(dateKey, regionKey, serviceKey, signingKey).forEach { it.fill(0) }
    return mapOf("x-amz-date" to time, "x-amz-content-sha256" to payloadHash, "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature")
}
private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
private fun hmac(key: ByteArray, value: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(value.toByteArray(Charsets.UTF_8))
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun awsEncode(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
    val ch = (byte.toInt() and 255).toChar()
    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~") ch.toString() else "%%%02X".format(byte.toInt() and 255)
}

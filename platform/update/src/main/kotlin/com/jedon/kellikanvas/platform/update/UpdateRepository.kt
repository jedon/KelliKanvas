package com.jedon.kellikanvas.platform.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class UpdateResponse(
    val statusCode: Int,
    val finalUrl: URI,
    val redirected: Boolean,
    val contentLength: Long?,
    val body: InputStream,
)

fun interface UpdateTransport {
    fun open(url: URI, maxBytes: Long): UpdateResponse
}

class OkHttpUpdateTransport(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) : UpdateTransport {
    override fun open(url: URI, maxBytes: Long): UpdateResponse {
        UpdateUrlPolicy.requireAllowed(url)
        val response = client.newCall(Request.Builder().url(url.toString()).get().build()).execute()
        val body = response.body
        val length = body.contentLength().takeIf { it >= 0 }
        if (length != null && length > maxBytes) {
            response.close()
            throw UpdateRejected("response exceeds size limit")
        }
        return UpdateResponse(
            statusCode = response.code,
            finalUrl = response.request.url.toUri(),
            redirected = response.isRedirect,
            contentLength = length,
            body = body.byteStream(),
        )
    }
}

interface CheckTimestampStore {
    fun lastCheckMillis(): Long?

    fun recordCheck(timestampMillis: Long)
}

class UpdateRepository(
    private val transport: UpdateTransport,
    private val verifier: ApkVerifier,
    private val updateCacheDir: File,
    private val timestampStore: CheckTimestampStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun check(manual: Boolean, installedVersionCode: Long): UpdateManifest? {
        val now = nowMillis()
        val previous = timestampStore?.lastCheckMillis()
        if (!UpdateCheckPolicy.shouldCheck(manual, now, previous)) return null
        timestampStore?.recordCheck(now)
        val response = checkedOpen(UpdateUrlPolicy.MANIFEST_URI, UpdateLimits.METADATA_MAX_BYTES.toLong())
        val bytes = response.body.use { it.readBounded(UpdateLimits.METADATA_MAX_BYTES.toLong()) }
        return UpdateManifest.parse(bytes).also { UpdateUrlPolicy.validateManifest(it, installedVersionCode) }
    }

    fun downloadAndVerify(manifest: UpdateManifest, installed: InstalledPackage): File {
        UpdateUrlPolicy.validateManifest(manifest, installed.versionCode)
        updateCacheDir.mkdirs()
        deleteStaleFiles()
        val partial = File(updateCacheDir, "kellikanvas-${manifest.versionCode}.apk.part")
        val destination = File(updateCacheDir, "kellikanvas-${manifest.versionCode}.apk")
        try {
            val checksumResponse = checkedOpen(manifest.checksumUrl, UpdateLimits.METADATA_MAX_BYTES.toLong())
            val checksumText =
                checksumResponse.body.use {
                    it.readBounded(UpdateLimits.METADATA_MAX_BYTES.toLong()).toString(Charsets.US_ASCII)
                }
            val independentHash = checksumText.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            if (independentHash != manifest.sha256) throw UpdateRejected("checksum file disagrees with manifest")

            val apkResponse = checkedOpen(manifest.apkUrl, UpdateLimits.APK_MAX_BYTES)
            if (apkResponse.contentLength != null && apkResponse.contentLength != manifest.sizeBytes) {
                apkResponse.body.close()
                throw UpdateRejected("APK content length mismatch")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            apkResponse.body.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > manifest.sizeBytes || total > UpdateLimits.APK_MAX_BYTES) {
                            throw UpdateRejected("APK exceeds declared size")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total != manifest.sizeBytes) throw UpdateRejected("APK is truncated")
            val actualHash = digest.digest().hex()
            if (actualHash != manifest.sha256) throw UpdateRejected("APK hash mismatch")
            verifier.verify(partial, manifest, installed)
            if (!partial.renameTo(destination)) throw UpdateRejected("could not finalize APK")
            return destination
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            if (error is UpdateRejected) throw error
            throw UpdateRejected("update download failed", error)
        }
    }

    fun deleteStaleFiles() {
        updateCacheDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    private fun checkedOpen(url: URI, maxBytes: Long): UpdateResponse {
        UpdateUrlPolicy.requireAllowed(url)
        val response = transport.open(url, maxBytes)
        if (response.redirected || response.statusCode in 300..399 || response.finalUrl != url) {
            response.body.close()
            throw UpdateRejected("redirects are forbidden")
        }
        if (response.statusCode != 200) {
            response.body.close()
            throw UpdateRejected("unexpected HTTP status ${response.statusCode}")
        }
        if (response.contentLength != null && response.contentLength > maxBytes) {
            response.body.close()
            throw UpdateRejected("response exceeds size limit")
        }
        return response
    }
}

private fun InputStream.readBounded(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw UpdateRejected("response exceeds size limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

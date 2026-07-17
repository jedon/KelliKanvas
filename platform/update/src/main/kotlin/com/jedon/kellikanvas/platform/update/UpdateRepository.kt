package com.jedon.kellikanvas.platform.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private val originPolicy: UpdateOriginPolicy = UpdateOriginPolicy.QNAP_LAN,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) : UpdateTransport {
    override fun open(url: URI, maxBytes: Long): UpdateResponse {
        originPolicy.requireAllowed(url)
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
            body = BoundedInputStream(body.byteStream(), maxBytes),
        )
    }
}

private class BoundedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int {
        checkCancellation()
        val value = super.read()
        if (value >= 0) addBytes(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancellation()
        val count = super.read(buffer, offset, length)
        if (count > 0) addBytes(count.toLong())
        return count
    }

    private fun addBytes(count: Long) {
        total += count
        if (total > maxBytes) throw UpdateRejected("response exceeds size limit")
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("update download cancelled")
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
    private val originPolicy: UpdateOriginPolicy = UpdateOriginPolicy.QNAP_LAN,
) {
    fun downloadAndVerify(manifest: UpdateManifest, installed: InstalledPackage): File {
        originPolicy.validateManifest(manifest, installed.versionCode)
        updateCacheDir.mkdirs()
        val partial = File(updateCacheDir, "kellikanvas-${manifest.versionCode}.apk.part")
        val destination = File(updateCacheDir, "kellikanvas-${manifest.versionCode}.apk")
        updateCacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach(File::delete)
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
            try {
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: Exception) {
                throw UpdateRejected("could not atomically finalize APK", error)
            }
            return destination
        } catch (error: Exception) {
            partial.delete()
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
        originPolicy.requireAllowed(url)
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

class AuthenticatedManifestRepository(
    private val transport: UpdateTransport,
    private val authenticator: ManifestAuthenticator,
    private val replayGuard: ReleaseReplayGuard,
    private val timestampStore: CheckTimestampStore,
    private val originPolicy: UpdateOriginPolicy = UpdateOriginPolicy.QNAP_LAN,
    private val manifestUri: URI = UpdateOriginPolicy.MANIFEST_URI,
    private val signatureUri: URI = UpdateOriginPolicy.SIGNATURE_URI,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun check(manual: Boolean, installedVersionCode: Long): UpdateManifest? {
        val now = nowMillis()
        if (!UpdateCheckPolicy.shouldCheck(manual, now, timestampStore.lastCheckMillis())) return null
        timestampStore.recordCheck(now)
        val manifestBytes = fetchKnown(manifestUri, UpdateLimits.METADATA_MAX_BYTES.toLong())
        val signatureBytes = fetchKnown(signatureUri, 1024)
        val manifest = authenticator.authenticate(manifestBytes, signatureBytes)
        originPolicy.validateManifest(manifest, installedVersionCode)
        replayGuard.accept(manifest)
        return manifest
    }

    private fun fetchKnown(uri: URI, maxBytes: Long): ByteArray {
        originPolicy.requireAllowed(uri)
        val response = transport.open(uri, maxBytes)
        response.body.use { body ->
            if (response.redirected || response.statusCode in 300..399 || response.finalUrl != uri) {
                throw UpdateRejected("redirects are forbidden")
            }
            if (response.statusCode != 200) throw UpdateRejected("unexpected HTTP status ${response.statusCode}")
            return body.readBounded(maxBytes)
        }
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

package com.jedon.kellikanvas.platform.update

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object UpdateLimits {
    const val PACKAGE_NAME = "com.jedon.kellikanvas"
    const val METADATA_MAX_BYTES = 64 * 1024
    const val APK_MAX_BYTES = 500L * 1024L * 1024L
    const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
}

class UpdateRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

data class UpdateManifest(
    val schema: Int,
    val sequence: Long = 0,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: URI,
    val checksumUrl: URI,
    val sizeBytes: Long,
    val sha256: String,
    val signerSha256: String = "",
) {
    fun canonicalBytes(): ByteArray = (
        """{"apkUrl":"$apkUrl","checksumUrl":"$checksumUrl","packageName":"$packageName","schema":$schema,"sequence":$sequence,"sha256":"$sha256","signerSha256":"$signerSha256","sizeBytes":$sizeBytes,"versionCode":$versionCode,"versionName":"$versionName"}""" +
            "\n"
        ).toByteArray(Charsets.UTF_8)

    companion object {
        private val field = Regex("\"([A-Za-z][A-Za-z0-9]*)\":(\"([^\"\\\\]*)\"|-?\\d+)")
        private val numericFields = setOf("schema", "sequence", "sizeBytes", "versionCode")
        private val stringFields =
            setOf(
                "apkUrl",
                "checksumUrl",
                "packageName",
                "sha256",
                "signerSha256",
                "versionName",
            )
        private val allFields = numericFields + stringFields

        fun parse(bytes: ByteArray): UpdateManifest {
            if (bytes.size > UpdateLimits.METADATA_MAX_BYTES) throw UpdateRejected("manifest exceeds 64 KiB")
            val json = bytes.toString(Charsets.UTF_8)
            if (!json.endsWith("\n") || !json.startsWith("{") || !json.dropLast(1).endsWith("}")) {
                throw UpdateRejected("malformed manifest")
            }
            val entries = json.dropLast(1).substring(1, json.length - 2).split(",")
            val matches = entries.map { field.matchEntire(it) ?: throw UpdateRejected("malformed manifest") }
            val keys = matches.map { it.groupValues[1] }
            if (keys.size != keys.distinct().size) throw UpdateRejected("duplicate manifest field")
            if (keys.toSet() != allFields) throw UpdateRejected("unknown or missing manifest field")
            val values = matches.associate { it.groupValues[1] to it.groupValues[2] }
            numericFields.forEach { name ->
                if (values.getValue(name).startsWith("\"")) throw UpdateRejected("wrong manifest field type: $name")
            }
            stringFields.forEach { name ->
                if (!values.getValue(name).startsWith("\"")) throw UpdateRejected("wrong manifest field type: $name")
            }
            fun string(name: String) = values.getValue(name).removeSurrounding("\"")
            return try {
                UpdateManifest(
                    schema = values.getValue("schema").toInt(),
                    sequence = values.getValue("sequence").toLong(),
                    packageName = string("packageName"),
                    versionCode = values.getValue("versionCode").toLong(),
                    versionName = string("versionName"),
                    apkUrl = URI(string("apkUrl")),
                    checksumUrl = URI(string("checksumUrl")),
                    sizeBytes = values.getValue("sizeBytes").toLong(),
                    sha256 = string("sha256"),
                    signerSha256 = string("signerSha256"),
                )
            } catch (error: Exception) {
                if (error is UpdateRejected) throw error
                throw UpdateRejected("malformed manifest", error)
            }.also {
                if (!it.canonicalBytes().contentEquals(bytes)) throw UpdateRejected("manifest is not canonical")
            }
        }
    }
}

class ManifestAuthenticator private constructor(private val publicKeys: List<PublicKey>) {
    constructor(publicKey: PublicKey) : this(listOf(publicKey))

    fun authenticate(bytes: ByteArray, signatureBytes: ByteArray): UpdateManifest {
        if (bytes.size > UpdateLimits.METADATA_MAX_BYTES) throw UpdateRejected("manifest exceeds 64 KiB")
        if (signatureBytes.isEmpty() || signatureBytes.size > 1024) throw UpdateRejected("invalid manifest signature")
        val valid =
            publicKeys.any { publicKey ->
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(bytes)
                    verify(signatureBytes)
                }
            }
        if (!valid) throw UpdateRejected("manifest signature verification failed")
        return UpdateManifest.parse(bytes)
    }

    companion object {
        fun fromPinnedBase64(encodedSubjectPublicKeyInfo: String): ManifestAuthenticator {
            val keys =
                encodedSubjectPublicKeyInfo.split(",").map { encoded ->
                    KeyFactory.getInstance("EC").generatePublic(
                        X509EncodedKeySpec(Base64.getDecoder().decode(encoded)),
                    )
                }
            if (keys.isEmpty()) throw IllegalArgumentException("at least one metadata public key is required")
            return ManifestAuthenticator(keys)
        }
    }
}

data class AuthenticatedRelease(val sequence: Long, val versionCode: Long)

interface AuthenticatedReleaseStore {
    fun highest(): AuthenticatedRelease?

    fun save(release: AuthenticatedRelease)
}

class InMemoryAuthenticatedReleaseStore : AuthenticatedReleaseStore {
    private var release: AuthenticatedRelease? = null

    override fun highest() = release

    override fun save(release: AuthenticatedRelease) {
        this.release = release
    }
}

class AndroidAuthenticatedReleaseStore(context: Context) : AuthenticatedReleaseStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("kellikanvas-authenticated-releases", Context.MODE_PRIVATE)

    override fun highest(): AuthenticatedRelease? {
        if (!preferences.contains("sequence") || !preferences.contains("versionCode")) return null
        return AuthenticatedRelease(
            sequence = preferences.getLong("sequence", -1),
            versionCode = preferences.getLong("versionCode", -1),
        )
    }

    override fun save(release: AuthenticatedRelease) {
        preferences.edit(commit = true) {
            putLong("sequence", release.sequence)
            putLong("versionCode", release.versionCode)
        }
    }
}

class ReleaseReplayGuard(private val store: AuthenticatedReleaseStore) {
    fun accept(manifest: UpdateManifest) {
        val previous = store.highest()
        if (previous != null &&
            (manifest.sequence <= previous.sequence || manifest.versionCode < previous.versionCode)
        ) {
            throw UpdateRejected("authenticated release metadata was replayed")
        }
        store.save(AuthenticatedRelease(manifest.sequence, manifest.versionCode))
    }
}

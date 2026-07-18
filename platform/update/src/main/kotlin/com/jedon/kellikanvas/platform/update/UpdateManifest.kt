package com.jedon.kellikanvas.platform.update

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
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

data class AuthenticatedUpdateEnvelope(
    val keyId: String,
    val payloadHash: String,
    val manifest: UpdateManifest,
)

class ManifestAuthenticator(private val publicKeys: Map<String, PublicKey>) {
    constructor(publicKey: PublicKey) : this(mapOf("default" to publicKey))

    fun authenticateEnvelope(envelopeBytes: ByteArray): AuthenticatedUpdateEnvelope {
        if (envelopeBytes.size > UpdateLimits.METADATA_MAX_BYTES) {
            throw UpdateRejected("authenticated control file exceeds 64 KiB")
        }
        try {
            val fields = parseEnvelopeFields(envelopeBytes)
            val keyId = fields.getValue("keyId")
            val publicKey = publicKeys[keyId] ?: throw UpdateRejected("unknown metadata signing key")
            val payload = Base64.getDecoder().decode(fields.getValue("payload"))
            val signatureBytes = Base64.getDecoder().decode(fields.getValue("signature"))
            if (signatureBytes.isEmpty() || signatureBytes.size > 1024) {
                throw UpdateRejected("invalid metadata signature")
            }
            val valid =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(payload)
                    verify(signatureBytes)
                }
            if (!valid) throw UpdateRejected("metadata authentication failed")
            val manifest = UpdateManifest.parse(payload)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            return AuthenticatedUpdateEnvelope(keyId, hash, manifest)
        } catch (error: UpdateRejected) {
            throw error
        } catch (_: GeneralSecurityException) {
            throw UpdateRejected("metadata authentication failed")
        } catch (_: IllegalArgumentException) {
            throw UpdateRejected("malformed authenticated control file")
        }
    }

    private fun parseEnvelopeFields(bytes: ByteArray): Map<String, String> {
        val json = bytes.toString(Charsets.UTF_8)
        if (!json.endsWith("\n") || !json.startsWith("{") || !json.dropLast(1).endsWith("}")) {
            throw UpdateRejected("malformed authenticated control file")
        }
        val field = Regex("\"([A-Za-z][A-Za-z0-9]*)\":(\"([^\"\\\\]*)\"|\\d+)")
        val matches =
            json.dropLast(1).substring(1, json.length - 2).split(",").map {
                field.matchEntire(it) ?: throw UpdateRejected("malformed authenticated control file")
            }
        val keys = matches.map { it.groupValues[1] }
        if (keys.size != keys.distinct().size ||
            keys.toSet() != setOf("envelopeSchema", "keyId", "payload", "signature")
        ) {
            throw UpdateRejected("unknown, duplicate, or missing envelope field")
        }
        val raw = matches.associate { it.groupValues[1] to it.groupValues[2] }
        if (raw.getValue("envelopeSchema") != "1") throw UpdateRejected("unsupported envelope schema")
        listOf("keyId", "payload", "signature").forEach { name ->
            if (!raw.getValue(name).startsWith("\"")) throw UpdateRejected("wrong envelope field type")
        }
        val values = raw.mapValues { (_, value) -> value.removeSurrounding("\"") }
        val canonical =
            """{"envelopeSchema":1,"keyId":"${values.getValue("keyId")}","payload":"${values.getValue("payload")}","signature":"${values.getValue("signature")}"}""" +
                "\n"
        if (!canonical.toByteArray().contentEquals(bytes)) throw UpdateRejected("control file is not canonical")
        return values
    }

    companion object {
        fun fromPinnedBase64(encodedSubjectPublicKeyInfo: String): ManifestAuthenticator {
            try {
                val keys =
                    encodedSubjectPublicKeyInfo.split(",").associate { pin ->
                        val parts = pin.split("=", limit = 2)
                        if (parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
                            throw IllegalArgumentException("invalid metadata key pin")
                        }
                        parts[0] to
                            KeyFactory.getInstance("EC").generatePublic(
                                X509EncodedKeySpec(Base64.getDecoder().decode(parts[1])),
                            )
                    }
                if (keys.isEmpty()) throw IllegalArgumentException("at least one metadata public key is required")
                return ManifestAuthenticator(keys)
            } catch (_: GeneralSecurityException) {
                throw UpdateRejected("invalid metadata public key configuration")
            } catch (_: IllegalArgumentException) {
                throw UpdateRejected("invalid metadata public key configuration")
            }
        }
    }
}

data class AuthenticatedRelease(
    val sequence: Long,
    val versionCode: Long,
    val payloadHash: String,
)

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
        if (!preferences.contains("sequence") ||
            !preferences.contains("versionCode") ||
            !preferences.contains("payloadHash")
        ) {
            return null
        }
        return AuthenticatedRelease(
            sequence = preferences.getLong("sequence", -1),
            versionCode = preferences.getLong("versionCode", -1),
            payloadHash = preferences.getString("payloadHash", null) ?: return null,
        )
    }

    override fun save(release: AuthenticatedRelease) {
        preferences.edit(commit = true) {
            putLong("sequence", release.sequence)
            putLong("versionCode", release.versionCode)
            putString("payloadHash", release.payloadHash)
        }
    }
}

class AndroidCheckTimestampStore(context: Context) : CheckTimestampStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("kellikanvas-update-check", Context.MODE_PRIVATE)

    override fun lastCheckMillis(): Long? {
        if (!preferences.contains("lastCheckMillis")) return null
        return preferences.getLong("lastCheckMillis", -1).takeIf { it >= 0 }
    }

    override fun recordCheck(timestampMillis: Long) {
        preferences.edit(commit = true) {
            putLong("lastCheckMillis", timestampMillis)
        }
    }
}

enum class ReplayDecision {
    NEW_RELEASE,
    IDEMPOTENT_RETRY,
}

class ReleaseReplayGuard(private val store: AuthenticatedReleaseStore) {
    fun accept(manifest: UpdateManifest, payloadHash: String): ReplayDecision {
        val previous = store.highest()
        if (previous != null) {
            if (manifest.sequence < previous.sequence || manifest.versionCode < previous.versionCode) {
                throw UpdateRejected("older authenticated release metadata was rejected")
            }
            if (manifest.sequence == previous.sequence) {
                if (manifest.versionCode == previous.versionCode && payloadHash == previous.payloadHash) {
                    return ReplayDecision.IDEMPOTENT_RETRY
                }
                throw UpdateRejected("conflicting authenticated metadata reused a release sequence")
            }
        }
        store.save(AuthenticatedRelease(manifest.sequence, manifest.versionCode, payloadHash))
        return ReplayDecision.NEW_RELEASE
    }
}

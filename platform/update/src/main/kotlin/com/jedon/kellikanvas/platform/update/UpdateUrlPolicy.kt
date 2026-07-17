package com.jedon.kellikanvas.platform.update

import java.net.URI

data class UpdateOrigin(val scheme: String, val host: String, val port: Int)

class UpdateOriginPolicy private constructor(private val allowed: Set<UpdateOrigin>) {
    fun requireAllowed(uri: URI) {
        val effectivePort =
            when {
                uri.port >= 0 -> uri.port
                uri.scheme == "https" -> 443
                uri.scheme == "http" -> 80
                else -> -1
            }
        val origin = UpdateOrigin(uri.scheme?.lowercase().orEmpty(), uri.host?.lowercase().orEmpty(), effectivePort)
        val accepted =
            origin in allowed &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.startsWith("/")
        if (!accepted) throw UpdateRejected("update URL is outside the allowed origin")
    }

    fun validateManifest(manifest: UpdateManifest, installedVersionCode: Long) {
        if (manifest.schema != 1) throw UpdateRejected("unsupported manifest schema")
        if (manifest.packageName != UpdateLimits.PACKAGE_NAME) throw UpdateRejected("wrong package")
        if (manifest.versionCode <= installedVersionCode) throw UpdateRejected("update is not newer")
        if (manifest.sizeBytes <= 0 || manifest.sizeBytes > UpdateLimits.APK_MAX_BYTES) {
            throw UpdateRejected("APK size is outside limits")
        }
        if (!manifest.sha256.matches(Regex("[0-9a-f]{64}"))) throw UpdateRejected("invalid SHA-256")
        if (!manifest.signerSha256.matches(Regex("[0-9A-F]{64}"))) throw UpdateRejected("invalid signer SHA-256")
        requireAllowed(manifest.apkUrl)
        requireAllowed(manifest.checksumUrl)
    }

    companion object {
        val QNAP_LAN = UpdateOriginPolicy(setOf(UpdateOrigin("http", "darklingnas", 8088)))
        val CONTROL_URI: URI = URI("http://darklingnas:8088/update-envelope.json")

        fun remoteHttps(host: String, port: Int = 443): UpdateOriginPolicy = UpdateOriginPolicy(
            setOf(UpdateOrigin("https", host.lowercase(), port)),
        )

        fun forTest(uri: URI): UpdateOriginPolicy {
            val port =
                if (uri.port >= 0) {
                    uri.port
                } else if (uri.scheme == "https") {
                    443
                } else {
                    80
                }
            return UpdateOriginPolicy(setOf(UpdateOrigin(uri.scheme, requireNotNull(uri.host), port)))
        }
    }
}

object UpdateUrlPolicy {
    val MANIFEST_URI = UpdateOriginPolicy.CONTROL_URI

    fun requireAllowed(uri: URI) = UpdateOriginPolicy.QNAP_LAN.requireAllowed(uri)

    fun validateManifest(manifest: UpdateManifest, installedVersionCode: Long) {
        UpdateOriginPolicy.QNAP_LAN.validateManifest(manifest, installedVersionCode)
    }
}

object UpdateCheckPolicy {
    fun shouldCheck(manual: Boolean, nowMillis: Long, lastCheckMillis: Long?): Boolean = manual ||
        lastCheckMillis == null ||
        nowMillis - lastCheckMillis > UpdateLimits.CHECK_INTERVAL_MILLIS
}

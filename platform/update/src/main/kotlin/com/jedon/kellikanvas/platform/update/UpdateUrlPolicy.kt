package com.jedon.kellikanvas.platform.update

import java.net.URI

object UpdateUrlPolicy {
    val MANIFEST_URI: URI = URI("http://darklingnas:8088/manifest.json")

    fun requireAllowed(uri: URI) {
        val allowed =
            uri.scheme == "http" &&
                uri.host?.equals("darklingnas", ignoreCase = true) == true &&
                uri.port == 8088 &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.startsWith("/")
        if (!allowed) throw UpdateRejected("update URL is outside the allowed origin")
    }

    fun validateManifest(manifest: UpdateManifest, installedVersionCode: Long) {
        if (manifest.schema != 1) throw UpdateRejected("unsupported manifest schema")
        if (manifest.packageName != UpdateLimits.PACKAGE_NAME) throw UpdateRejected("wrong package")
        if (manifest.versionCode <= installedVersionCode) throw UpdateRejected("update is not newer")
        if (manifest.sizeBytes <= 0 || manifest.sizeBytes > UpdateLimits.APK_MAX_BYTES) {
            throw UpdateRejected("APK size is outside limits")
        }
        if (!manifest.sha256.matches(Regex("[0-9a-f]{64}"))) throw UpdateRejected("invalid SHA-256")
        requireAllowed(manifest.apkUrl)
        requireAllowed(manifest.checksumUrl)
    }
}

object UpdateCheckPolicy {
    fun shouldCheck(manual: Boolean, nowMillis: Long, lastCheckMillis: Long?): Boolean = manual ||
        lastCheckMillis == null ||
        nowMillis - lastCheckMillis > UpdateLimits.CHECK_INTERVAL_MILLIS
}

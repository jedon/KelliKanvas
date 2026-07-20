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
        private const val QNAP_HOSTNAME = "darklingnas"
        private const val QNAP_STATIC_LAN_IP = "192.168.68.81"
        private const val QNAP_PORT = 8088
        private val IPV4_LITERAL = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

        /** Canonical QNAP LAN hostname used by published envelopes. */
        val QNAP_LAN = qnapLan()
        val CONTROL_URI: URI = URI("http://$QNAP_HOSTNAME:$QNAP_PORT/update-envelope.json")
        val CONTROL_URI_LAN_IP: URI = URI("http://$QNAP_STATIC_LAN_IP:$QNAP_PORT/update-envelope.json")
        val CONTROL_URIS: List<URI> = qnapControlUris()

        /**
         * QNAP LAN origin set: hostname, optional cached last-known-good LAN IP,
         * and the static default IP. The cached IP must be an IPv4 literal recorded
         * from a prior successful NAS connection; anything else is ignored so the
         * allowlist can never widen to arbitrary hosts.
         */
        fun qnapLan(cachedLanIp: String? = null): UpdateOriginPolicy {
            val origins = linkedSetOf(UpdateOrigin("http", QNAP_HOSTNAME, QNAP_PORT))
            validCachedLanIp(cachedLanIp)?.let { origins += UpdateOrigin("http", it, QNAP_PORT) }
            // Static LAN IP alias when TV DNS does not resolve darklingnas.
            origins += UpdateOrigin("http", QNAP_STATIC_LAN_IP, QNAP_PORT)
            return UpdateOriginPolicy(origins)
        }

        /** Control-file fetch order: hostname first, cached LAN IP, static LAN IP. */
        fun qnapControlUris(cachedLanIp: String? = null): List<URI> {
            val cached = validCachedLanIp(cachedLanIp)
            return listOfNotNull(
                CONTROL_URI,
                cached?.let { URI("http://$it:$QNAP_PORT/update-envelope.json") },
                CONTROL_URI_LAN_IP,
            ).distinct()
        }

        private fun validCachedLanIp(ip: String?): String? = ip?.trim()?.takeIf { candidate ->
            IPV4_LITERAL.matches(candidate) && candidate.split('.').all { octet -> octet.toInt() <= 255 }
        }

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

package com.jedon.kellikanvas.source.nas

import com.jedon.kellikanvas.logging.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Finds a reachable household NAS host instead of assuming a static LAN IP.
 *
 * Candidate order: DNS resolution of [hostname], the cached last known-good IP,
 * the [staticDefaultIp], then SSDP/DLNA [discover]. Each candidate must pass the
 * caller-supplied [probe] (e.g. a TCP connect) before it is returned.
 */
class NasHostResolver(
    private val hostname: String,
    private val staticDefaultIp: String,
    private val cache: NasHostCache,
    private val probe: suspend (host: String) -> Boolean,
    private val dnsLookup: suspend (hostname: String) -> String? = ::inetAddressLookup,
    private val discover: suspend () -> String? = { null },
    private val dnsTimeoutMillis: Long = DNS_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Most recent successful resolution, for the Diagnostics screen. */
    @Volatile
    var lastResolution: NasResolution? = null
        private set

    /** Returns the first probe-verified candidate, or null when every candidate fails. */
    suspend fun resolve(): NasResolution? {
        val tried = mutableSetOf<String>()
        val resolution =
            tryCandidate(NasResolutionPath.HOSTNAME, tried) {
                withTimeoutOrNull(dnsTimeoutMillis) { dnsLookup(hostname) }
            }
                ?: tryCandidate(NasResolutionPath.CACHED_IP, tried) { cache.get() }
                ?: tryCandidate(NasResolutionPath.STATIC_DEFAULT, tried) { staticDefaultIp }
                ?: tryCandidate(NasResolutionPath.DISCOVERY, tried) { discover() }
        if (resolution != null) {
            lastResolution = resolution
            DiagLog.i(TAG, "Resolved NAS host=${resolution.host} via ${resolution.path}")
        } else {
            DiagLog.w(TAG, "All NAS candidates failed (hostname=$hostname static=$staticDefaultIp)")
        }
        return resolution
    }

    /**
     * Records a working NAS address after a real connection succeeds. Accepts bare
     * hosts, host:port pairs, and URLs, but persists only IPv4 literals; hostnames
     * are ignored so the cache always holds a directly connectable address.
     */
    fun recordKnownGoodIp(host: String) {
        val ip = extractIpv4Literal(host) ?: return
        if (cache.get() == ip) return
        cache.set(ip)
        DiagLog.i(TAG, "Recorded known-good NAS IP $ip")
    }

    private suspend fun tryCandidate(
        path: NasResolutionPath,
        tried: MutableSet<String>,
        candidate: suspend () -> String?,
    ): NasResolution? {
        val host =
            try {
                candidate()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "NAS candidate lookup failed for $path", failure)
                null
            }
        if (host.isNullOrBlank()) {
            DiagLog.d(TAG, "NAS candidate $path unavailable")
            return null
        }
        if (!tried.add(host)) {
            DiagLog.d(TAG, "NAS candidate $path host=$host already probed")
            return null
        }
        val reachable =
            try {
                probe(host)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagLog.w(TAG, "NAS probe crashed for $path host=$host", failure)
                false
            }
        DiagLog.d(TAG, "NAS candidate $path host=$host probe=${if (reachable) "ok" else "failed"}")
        return if (reachable) NasResolution(host, path, nowMillis()) else null
    }

    companion object {
        private const val TAG = "NasHostResolver"
        const val DNS_TIMEOUT_MILLIS: Long = 3_000

        /** Extracts an IPv4 literal from a bare host, host:port, or URL; null otherwise. */
        fun extractIpv4Literal(host: String): String? {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return null
            val hostPart =
                if (trimmed.contains("://")) {
                    runCatching { URI(trimmed).host }.getOrNull() ?: return null
                } else {
                    trimmed.substringBefore('/').substringBefore(':')
                }
            val isIpv4 =
                IPV4_LITERAL.matches(hostPart) &&
                    hostPart.split('.').all { octet -> octet.toInt() <= 255 }
            return hostPart.takeIf { isIpv4 }
        }

        private val IPV4_LITERAL = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
    }
}

/**
 * Default DNS lookup. [InetAddress.getByName] has no timeout parameter, so callers
 * bound it externally; [NasHostResolver.resolve] wraps this in `withTimeoutOrNull`.
 */
private suspend fun inetAddressLookup(hostname: String): String? = runInterruptible(Dispatchers.IO) {
    try {
        InetAddress.getByName(hostname).hostAddress
    } catch (_: UnknownHostException) {
        null
    }
}

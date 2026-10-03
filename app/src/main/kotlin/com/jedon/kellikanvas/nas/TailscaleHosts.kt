package com.jedon.kellikanvas.nas

import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults

/**
 * Pure Tailscale address rules. The Android VPN lookup lives in [TailscaleNasLookup];
 * this object only decides which names to query and which answers are the NAS.
 */
object TailscaleHosts {
    const val PACKAGE: String = "com.tailscale.ipn"
    const val MAGIC_DNS_IPV4: String = "100.100.100.100"

    const val DISCONNECTED_MESSAGE: String =
        "Tailscale isn't connected. Open it and turn it on to reach DarklingNAS."

    /** Tailscale's MagicDNS resolver, which is not a peer address. */
    fun isMagicDnsAddress(host: String): Boolean {
        val normalized = normalizeAddress(host).lowercase()
        return normalized == MAGIC_DNS_IPV4 || normalized == "fd7a:115c:a1e0::53"
    }

    /** True for addresses inside Tailscale's IPv4 CGNAT range, 100.64.0.0/10. */
    fun isTailscaleIpv4(host: String): Boolean {
        val parts = normalizeAddress(host).split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun isTailscaleIpv6(host: String): Boolean {
        val normalized = normalizeAddress(host).lowercase()
        return normalized.startsWith("fd7a:115c:a1e0:")
    }

    /**
     * Exact DNS names to ask the Tailscale resolver. Search domains are appended
     * because a bare lookup does not apply them, and `.local` is mDNS rather than MagicDNS.
     */
    fun lookupNames(hostname: String, searchDomains: List<String>): List<String> {
        val short = hostname.trim().trimEnd('.')
        if (short.isEmpty()) return emptyList()
        val names = mutableListOf<String>()
        for (domain in searchDomains) {
            val suffix = domain.trim().trim('.')
            if (suffix.isEmpty() || suffix.equals("local", ignoreCase = true)) continue
            names += "$short.$suffix"
        }
        names += short
        return names.distinct()
    }

    /**
     * Probe order: Tailscale IPv4, then Tailscale IPv6, then any other answer.
     * The MagicDNS resolver address itself is never a connect target.
     */
    fun candidateHosts(addresses: List<String>): List<String> {
        val usable = addresses
            .map(::normalizeAddress)
            .filter { it.isNotEmpty() && !isMagicDnsAddress(it) }
            .distinct()
        val v4 = usable.filter(::isTailscaleIpv4)
        val v6 = usable.filter(::isTailscaleIpv6)
        val other = usable.filter { it !in v4 && it !in v6 }
        return v4 + v6 + other
    }

    /** True when a VPN link is publishing Tailscale DNS or a Tailscale interface address. */
    fun linkLooksLikeTailscale(dnsServers: List<String>, interfaceAddresses: List<String>): Boolean {
        if (dnsServers.any(::isMagicDnsAddress)) return true
        if (interfaceAddresses.any { isTailscaleIpv4(it) && !isMagicDnsAddress(it) }) return true
        if (interfaceAddresses.any { isTailscaleIpv6(it) && !isMagicDnsAddress(it) }) return true
        return false
    }

    /**
     * Saved SMB hosts that belong to the household NAS, including a previous
     * Tailscale address or MagicDNS name, so a later lookup can replace them.
     */
    fun isHouseholdNasHost(host: String): Boolean {
        val normalized = normalizeAddress(host)
        if (normalized.isEmpty() || isMagicDnsAddress(normalized)) return false
        if (HouseholdNasDefaults.HOST_CANDIDATES.any { it.equals(normalized, ignoreCase = true) }) return true
        if (normalized.equals(HouseholdNasDefaults.HOSTNAME, ignoreCase = true)) return true
        if (isTailscaleIpv4(normalized)) return true
        val lower = normalized.lowercase()
        val firstLabel = lower.substringBefore('.')
        return lower.endsWith(".ts.net") &&
            firstLabel.equals(HouseholdNasDefaults.HOSTNAME, ignoreCase = true)
    }

    fun normalizeAddress(host: String): String = host.substringBefore('%').trim().trimEnd('.')
}

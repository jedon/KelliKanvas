package com.jedon.kellikanvas.nas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TailscaleHostsTest {
    @Test
    fun `tailscale ipv4 is the cgnat range excluding the magic dns resolver`() {
        assertThat(TailscaleHosts.isTailscaleIpv4("100.64.0.1")).isTrue()
        assertThat(TailscaleHosts.isTailscaleIpv4("100.127.255.254")).isTrue()
        assertThat(TailscaleHosts.isTailscaleIpv4("100.100.100.100")).isTrue()
        assertThat(TailscaleHosts.isTailscaleIpv4("100.63.255.255")).isFalse()
        assertThat(TailscaleHosts.isTailscaleIpv4("100.128.0.1")).isFalse()
        assertThat(TailscaleHosts.isTailscaleIpv4("192.168.68.62")).isFalse()
        assertThat(TailscaleHosts.isMagicDnsAddress("100.100.100.100")).isTrue()
        assertThat(TailscaleHosts.isMagicDnsAddress("fd7a:115c:a1e0::53")).isTrue()
        assertThat(TailscaleHosts.isMagicDnsAddress("fd7a:115c:a1e0::53%tun0")).isTrue()
    }

    @Test
    fun `lookup names append search domains and skip mDNS local`() {
        assertThat(TailscaleHosts.lookupNames("darklingnas", listOf("tail-net.ts.net", "local", "")))
            .containsExactly("darklingnas.tail-net.ts.net", "darklingnas")
            .inOrder()
    }

    @Test
    fun `candidate hosts prefer tailscale addresses and drop the resolver`() {
        assertThat(
            TailscaleHosts.candidateHosts(
                listOf(
                    "192.168.68.62",
                    "100.100.100.100",
                    "fd7a:115c:a1e0:abcd::1",
                    "100.111.22.33",
                    "100.111.22.33",
                ),
            ),
        ).containsExactly("100.111.22.33", "fd7a:115c:a1e0:abcd::1", "192.168.68.62").inOrder()
    }

    @Test
    fun `link detection accepts magic dns or a tailscale interface address`() {
        assertThat(
            TailscaleHosts.linkLooksLikeTailscale(
                dnsServers = listOf("100.100.100.100"),
                interfaceAddresses = emptyList(),
            ),
        ).isTrue()
        assertThat(
            TailscaleHosts.linkLooksLikeTailscale(
                dnsServers = listOf("8.8.8.8"),
                interfaceAddresses = listOf("100.101.1.2"),
            ),
        ).isTrue()
        assertThat(
            TailscaleHosts.linkLooksLikeTailscale(
                dnsServers = listOf("8.8.8.8"),
                interfaceAddresses = listOf("192.168.1.20"),
            ),
        ).isFalse()
    }

    @Test
    fun `household hosts include the baked-in names and a live tailscale address`() {
        assertThat(TailscaleHosts.isHouseholdNasHost("192.168.68.62")).isTrue()
        assertThat(TailscaleHosts.isHouseholdNasHost("DarklingNAS")).isTrue()
        assertThat(TailscaleHosts.isHouseholdNasHost("darklingnas.local")).isTrue()
        assertThat(TailscaleHosts.isHouseholdNasHost("100.111.22.33")).isTrue()
        assertThat(TailscaleHosts.isHouseholdNasHost("darklingnas.tail-net.ts.net")).isTrue()
        assertThat(TailscaleHosts.isHouseholdNasHost("100.100.100.100")).isFalse()
        assertThat(TailscaleHosts.isHouseholdNasHost("other-nas.tail-net.ts.net")).isFalse()
        assertThat(TailscaleHosts.isHouseholdNasHost("example.com")).isFalse()
    }
}

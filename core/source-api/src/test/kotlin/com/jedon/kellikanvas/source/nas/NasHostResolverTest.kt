package com.jedon.kellikanvas.source.nas

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NasHostResolverTest {
    private val cache = FakeNasHostCache()

    @Test
    fun `hostname wins when dns resolves and probe succeeds`() = runTest {
        val resolver =
            resolver(
                dnsLookup = { "192.168.68.99" },
                probe = { it == "192.168.68.99" },
            )

        val resolution = resolver.resolve()

        assertThat(resolution).isNotNull()
        assertThat(resolution!!.host).isEqualTo("192.168.68.99")
        assertThat(resolution.path).isEqualTo(NasResolutionPath.HOSTNAME)
        assertThat(resolver.lastResolution).isEqualTo(resolution)
    }

    @Test
    fun `falls through to cached ip when dns fails`() = runTest {
        cache.value = "192.168.68.90"
        val resolver =
            resolver(
                dnsLookup = { null },
                probe = { it == "192.168.68.90" },
            )

        val resolution = resolver.resolve()

        assertThat(resolution!!.host).isEqualTo("192.168.68.90")
        assertThat(resolution.path).isEqualTo(NasResolutionPath.CACHED_IP)
    }

    @Test
    fun `falls through to static default when cache is empty`() = runTest {
        val resolver =
            resolver(
                dnsLookup = { null },
                probe = { it == STATIC_IP },
            )

        val resolution = resolver.resolve()

        assertThat(resolution!!.host).isEqualTo(STATIC_IP)
        assertThat(resolution.path).isEqualTo(NasResolutionPath.STATIC_DEFAULT)
    }

    @Test
    fun `falls through to discovery when earlier candidates fail probe`() = runTest {
        cache.value = "192.168.68.90"
        val resolver =
            resolver(
                dnsLookup = { null },
                probe = { it == "192.168.68.123" },
                discover = { "192.168.68.123" },
            )

        val resolution = resolver.resolve()

        assertThat(resolution!!.host).isEqualTo("192.168.68.123")
        assertThat(resolution.path).isEqualTo(NasResolutionPath.DISCOVERY)
    }

    @Test
    fun `returns null and keeps lastResolution unset when all candidates fail`() = runTest {
        val resolver =
            resolver(
                dnsLookup = { null },
                probe = { false },
                discover = { null },
            )

        assertThat(resolver.resolve()).isNull()
        assertThat(resolver.lastResolution).isNull()
    }

    @Test
    fun `dns lookup slower than timeout does not hang resolution`() = runTest {
        cache.value = "192.168.68.90"
        val resolver =
            resolver(
                dnsLookup = {
                    delay(60_000)
                    "192.168.68.99"
                },
                probe = { it == "192.168.68.90" },
            )

        val resolution = resolver.resolve()

        assertThat(resolution!!.path).isEqualTo(NasResolutionPath.CACHED_IP)
    }

    @Test
    fun `probe and lookup exceptions are treated as candidate failures`() = runTest {
        val resolver =
            resolver(
                dnsLookup = { throw IllegalStateException("dns crashed") },
                probe = { host ->
                    if (host == STATIC_IP) true else throw IllegalStateException("probe crashed")
                },
                discover = { null },
            )

        val resolution = resolver.resolve()

        assertThat(resolution!!.path).isEqualTo(NasResolutionPath.STATIC_DEFAULT)
    }

    @Test
    fun `duplicate candidates are probed only once`() = runTest {
        cache.value = STATIC_IP
        val probed = mutableListOf<String>()
        val resolver =
            resolver(
                dnsLookup = { null },
                probe = { host ->
                    probed += host
                    false
                },
                discover = { null },
            )

        assertThat(resolver.resolve()).isNull()
        assertThat(probed).containsExactly(STATIC_IP)
    }

    @Test
    fun `recordKnownGoodIp persists only ip literals`() = runTest {
        val resolver = resolver(dnsLookup = { null }, probe = { false })

        resolver.recordKnownGoodIp("darklingnas")
        assertThat(cache.value).isNull()

        resolver.recordKnownGoodIp("999.1.1.1")
        assertThat(cache.value).isNull()

        resolver.recordKnownGoodIp("192.168.68.90")
        assertThat(cache.value).isEqualTo("192.168.68.90")
    }

    @Test
    fun `recordKnownGoodIp extracts host from urls and host-port pairs`() = runTest {
        val resolver = resolver(dnsLookup = { null }, probe = { false })

        resolver.recordKnownGoodIp("http://192.168.68.91:8200/rootDesc.xml")
        assertThat(cache.value).isEqualTo("192.168.68.91")

        resolver.recordKnownGoodIp("192.168.68.92:8200")
        assertThat(cache.value).isEqualTo("192.168.68.92")

        resolver.recordKnownGoodIp("http://darklingnas:8200/rootDesc.xml")
        assertThat(cache.value).isEqualTo("192.168.68.92")
    }

    @Test
    fun `resolver reads cache through NasHostCache interface`() = runTest {
        cache.value = "192.168.68.93"
        val resolver = resolver(dnsLookup = { null }, probe = { true })

        // DNS returned null, so the first probed candidate comes from the cache.
        val resolution = resolver.resolve()

        assertThat(resolution!!.host).isEqualTo("192.168.68.93")
        assertThat(resolution.path).isEqualTo(NasResolutionPath.CACHED_IP)
    }

    private fun resolver(
        dnsLookup: suspend (String) -> String?,
        probe: suspend (String) -> Boolean,
        discover: suspend () -> String? = { null },
    ): NasHostResolver = NasHostResolver(
        hostname = "darklingnas",
        staticDefaultIp = STATIC_IP,
        cache = cache,
        probe = probe,
        dnsLookup = dnsLookup,
        discover = discover,
    )

    private class FakeNasHostCache : NasHostCache {
        var value: String? = null

        override fun get(): String? = value

        override fun set(ip: String) {
            value = ip
        }
    }

    private companion object {
        const val STATIC_IP = "192.168.68.81"
    }
}

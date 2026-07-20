package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.URI

class DlnaManualResolverTest {
    @Test
    fun `builtInHostCandidates includes known QNAP addresses`() {
        assertThat(DlnaManualResolver.BUILT_IN_HOST_CANDIDATES)
            .containsExactly(
                "http://192.168.68.81:8200/rootDesc.xml",
                "192.168.68.81:8200",
                "192.168.68.81",
                "darklingnas",
                "darklingnas.local",
                "DarklingNAS",
            )
            .inOrder()
        assertThat(DlnaManualResolver.builtInDescriptionCandidates())
            .contains("http://192.168.68.81:8200/rootDesc.xml")
    }

    @Test
    fun `resolveBuiltIn returns first successful host`() = runTest {
        val loaded = mutableListOf<URI>()
        val descriptionXml =
            """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://192.168.68.81:8200/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>DarklingNAS</friendlyName>
                <UDN>uuid:qnap-representative</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>ctl/ContentDir</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
            """.trimIndent().toByteArray()
        val resolver =
            DlnaManualResolver(
                loadDescription = { uri ->
                    loaded += uri
                    if (uri.toString() == "http://192.168.68.81:8200/rootDesc.xml") {
                        descriptionXml
                    } else {
                        throw IOException("not this one")
                    }
                },
            )

        val result = resolver.resolveBuiltIn()

        assertThat(result.matchedHost).isEqualTo("http://192.168.68.81:8200/rootDesc.xml")
        assertThat(result.profile.serverUdn).isEqualTo("uuid:qnap-representative")
        assertThat(result.profile.descriptionLocation)
            .isEqualTo(URI("http://192.168.68.81:8200/rootDesc.xml"))
        assertThat(loaded.map(URI::toString)).containsExactly(
            "http://192.168.68.81:8200/rootDesc.xml",
        )
    }

    @Test
    fun `resolveBuiltIn tries preferred hosts before built-in candidates`() = runTest {
        val loaded = mutableListOf<URI>()
        val resolver =
            DlnaManualResolver(
                loadDescription = { uri ->
                    loaded += uri
                    if (uri.host == "192.168.50.20") {
                        fixture("dlna/qnap-representative-description.xml")
                    } else {
                        throw IOException("not this one")
                    }
                },
            )

        val result = resolver.resolveBuiltIn(preferredHosts = listOf("192.168.50.20"))

        assertThat(result.matchedHost).isEqualTo("192.168.50.20")
        assertThat(loaded.first().host).isEqualTo("192.168.50.20")
    }

    @Test
    fun `descriptionCandidates returns trimmed URI when input contains scheme`() {
        assertThat(DlnaManualResolver.descriptionCandidates("  http://192.168.1.2/rootDesc.xml  "))
            .containsExactly("http://192.168.1.2/rootDesc.xml")
    }

    @Test
    fun `descriptionCandidates prefers port 8200 for bare host`() {
        assertThat(DlnaManualResolver.descriptionCandidates("192.168.1.2"))
            .containsExactly(
                "http://192.168.1.2:8200/rootDesc.xml",
                "http://192.168.1.2/rootDesc.xml",
                "http://192.168.1.2/description.xml",
            )
            .inOrder()
    }

    @Test
    fun `descriptionCandidates skips port 8200 path when host already has port`() {
        assertThat(DlnaManualResolver.descriptionCandidates("192.168.1.2:9000"))
            .containsExactly(
                "http://192.168.1.2:9000/rootDesc.xml",
                "http://192.168.1.2:9000/description.xml",
            )
    }

    @Test
    fun `resolve builds profile from description URL`() = runTest {
        val resolver =
            DlnaManualResolver(
                loadDescription = { fixture("dlna/qnap-representative-description.xml") },
            )

        val profile = resolver.resolve("http://192.168.50.20:8200/root.xml")

        assertThat(profile.serverUdn).isEqualTo("uuid:qnap-representative")
        assertThat(profile.descriptionLocation).isEqualTo(URI("http://192.168.50.20:8200/root.xml"))
        assertThat(profile.controlUrl).isEqualTo(URI("http://192.168.50.20:8200/base/control/content"))
        assertThat(profile.contentDirectoryVersion).isEqualTo(2)
        assertThat(profile.id).isEqualTo(stableDlnaProfileId("uuid:qnap-representative"))
    }

    @Test
    fun `resolve tries candidates until one succeeds`() = runTest {
        val loaded = mutableListOf<URI>()
        val resolver =
            DlnaManualResolver(
                loadDescription = { uri ->
                    loaded += uri
                    when (uri.toString()) {
                        "http://192.168.50.20:8200/rootDesc.xml" ->
                            fixture("dlna/qnap-representative-description.xml")
                        else -> throw IOException("not found")
                    }
                },
            )

        val profile = resolver.resolve("192.168.50.20")

        assertThat(profile.serverUdn).isEqualTo("uuid:qnap-representative")
        assertThat(profile.descriptionLocation).isEqualTo(URI("http://192.168.50.20:8200/rootDesc.xml"))
        assertThat(loaded.map(URI::toString)).containsExactly(
            "http://192.168.50.20:8200/rootDesc.xml",
        )
    }

    @Test
    fun `resolve throws unavailable when all candidates fail`() = runTest {
        val rootCause = IOException("network down")
        val resolver =
            DlnaManualResolver(
                loadDescription = { throw rootCause },
            )

        val failure = runCatching { resolver.resolve("192.168.50.20") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DlnaSourceUnavailableException::class.java)
        assertThat(failure?.cause).isSameInstanceAs(rootCause)
    }

    @Test
    fun `resolve rethrows cancellation`() = runTest {
        val cancellation = CancellationException("stop")
        val resolver =
            DlnaManualResolver(
                loadDescription = { throw cancellation },
            )

        val failure = runCatching { resolver.resolve("http://192.168.50.20:8200/root.xml") }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
    }

    private fun fixture(path: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ).use { it.readBytes() }
}

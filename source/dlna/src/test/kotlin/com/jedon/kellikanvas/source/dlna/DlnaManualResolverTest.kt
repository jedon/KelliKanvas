package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.URI

class DlnaManualResolverTest {
    @Test
    fun `descriptionCandidates returns trimmed URI when input contains scheme`() {
        assertThat(DlnaManualResolver.descriptionCandidates("  http://192.168.1.2/rootDesc.xml  "))
            .containsExactly("http://192.168.1.2/rootDesc.xml")
    }

    @Test
    fun `descriptionCandidates tries common paths for bare host`() {
        assertThat(DlnaManualResolver.descriptionCandidates("192.168.1.2"))
            .containsExactly(
                "http://192.168.1.2/rootDesc.xml",
                "http://192.168.1.2/description.xml",
                "http://192.168.1.2:8200/rootDesc.xml",
            )
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
                        "http://192.168.50.20/rootDesc.xml",
                        "http://192.168.50.20/description.xml",
                        -> throw IOException("not found")
                        else -> fixture("dlna/qnap-representative-description.xml")
                    }
                },
            )

        val profile = resolver.resolve("192.168.50.20")

        assertThat(profile.serverUdn).isEqualTo("uuid:qnap-representative")
        assertThat(profile.descriptionLocation).isEqualTo(URI("http://192.168.50.20:8200/rootDesc.xml"))
        assertThat(loaded.map(URI::toString)).containsExactly(
            "http://192.168.50.20/rootDesc.xml",
            "http://192.168.50.20/description.xml",
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

    private fun fixture(path: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }
}

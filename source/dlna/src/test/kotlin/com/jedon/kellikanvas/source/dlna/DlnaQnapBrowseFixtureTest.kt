package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.net.URI

class DlnaQnapBrowseFixtureTest {
    @Test
    fun `stableObjectId prefixes raw ContentDirectory ids once`() {
        val profile = sampleProfile(controlUrl = URI("http://192.168.1.8:8200/ctl/ContentDir"))

        assertThat(profile.stableObjectId("0").value).isEqualTo("uuid:qnap-fixture\u00000")
        assertThat(profile.stableObjectId("uuid:qnap-fixture\u00003").value)
            .isEqualTo("uuid:qnap-fixture\u00003")
        assertThat(profile.rootFolder.objectId).isEqualTo(profile.stableObjectId("0"))
    }

    @Test
    fun `raw root object id fails before SOAP while stable root lists QNAP containers`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(QNAP_ROOT_BROWSE_SOAP))
            val controlUrl = server.url("/ctl/ContentDir").toUri()
            val profile = sampleProfile(controlUrl = controlUrl)
            val adapter =
                DlnaSourceAdapter(
                    profile = profile,
                    backend =
                    NetworkDlnaBackend(
                        serverUdn = profile.serverUdn,
                        contentDirectory =
                        ContentDirectoryClient(
                            httpClient = OkHttpClient(),
                            controlUrl = controlUrl,
                            serverUdn = profile.serverUdn,
                            version = 1,
                        ),
                        photoLoader =
                        DlnaPhotoLoader(
                            OkHttpClient(),
                            DlnaEndpointPolicy(server.url("/rootDesc.xml").toUri()) { it.isLoopbackAddress },
                        ),
                    ),
                )

            val rawFailure =
                runCatching {
                    adapter.listChildren(
                        FolderRef(profile.id, ProviderObjectId("0")),
                        cursor = null,
                        limit = 10,
                    )
                }.exceptionOrNull()

            assertThat(rawFailure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            assertThat((rawFailure as SourceFailure).safeDetail)
                .contains("Object does not belong to configured DLNA server")
            assertThat(server.requestCount).isEqualTo(0)

            val page = adapter.listChildren(profile.rootFolder, cursor = null, limit = 10)
            val folders =
                page.items.mapNotNull { entry ->
                    (entry as? SourceEntry.Folder)?.let { it.name to it.ref.objectId.value }
                }

            assertThat(folders).containsExactly(
                "Music" to "uuid:qnap-fixture\u00001",
                "Videos" to "uuid:qnap-fixture\u00002",
                "Photos" to "uuid:qnap-fixture\u00003",
            ).inOrder()
            assertThat(server.takeRequest().path).isEqualTo("/ctl/ContentDir")
        }
    }

    private fun sampleProfile(controlUrl: URI) = DlnaProfile(
        id = SourceProfileId("dlna-qnap-fixture"),
        serverUdn = "uuid:qnap-fixture",
        descriptionLocation = URI("http://192.168.1.8:8200/rootDesc.xml"),
        controlUrl = controlUrl,
        contentDirectoryVersion = 1,
    )

    private companion object {
        // Sanitized from a live QNAP TurboNAS ContentDirectory Browse of object 0.
        val QNAP_ROOT_DIDL =
            """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <container id="1" parentID="0" restricted="1" childCount="5"><dc:title>Music</dc:title><upnp:class>object.container</upnp:class></container>
              <container id="2" parentID="0" restricted="1" childCount="4"><dc:title>Videos</dc:title><upnp:class>object.container</upnp:class></container>
              <container id="3" parentID="0" restricted="1" childCount="3"><dc:title>Photos</dc:title><upnp:class>object.container</upnp:class></container>
            </DIDL-Lite>
            """.trimIndent()

        val QNAP_ROOT_BROWSE_SOAP =
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>${escapeXml(QNAP_ROOT_DIDL)}</Result>
            <NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches>
            </u:BrowseResponse></s:Body></s:Envelope>
            """.trimIndent()

        fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

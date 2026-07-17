package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test

class DlnaXmlTest {
    @Test
    fun `device description resolves ContentDirectory v2 control URL`() {
        val xml =
            """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>Living Room NAS</friendlyName><UDN>uuid:qnap-1</UDN>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:ContentDirectory:2</serviceType>
                  <controlURL>/upnp/control/content</controlURL>
                </service></serviceList>
              </device>
            </root>
            """.trimIndent()

        val result =
            DeviceDescriptionParser().parse(
                xml.encodeToByteArray(),
                "http://192.168.1.8:8200/root.xml",
            )

        assertThat(result.udn).isEqualTo("uuid:qnap-1")
        assertThat(result.version).isEqualTo(2)
        assertThat(result.controlUrl.toString()).isEqualTo("http://192.168.1.8:8200/upnp/control/content")
    }

    @Test
    fun `bounded XML rejects doctype depth text attributes and body size`() {
        val parser = DidlLiteParser()
        assertThrows(DlnaProtocolException::class.java) {
            parser.parse("""<!DOCTYPE x [<!ENTITY a "boom">]><DIDL-Lite>&a;</DIDL-Lite>""".encodeToByteArray(), "uuid:x")
        }
        assertThrows(DlnaProtocolException::class.java) {
            parser.parse(("<a>".repeat(33) + "</a>".repeat(33)).encodeToByteArray(), "uuid:x")
        }
        assertThrows(DlnaProtocolException::class.java) {
            parser.parse("<DIDL-Lite><item id=\"${"x".repeat(4097)}\"/></DIDL-Lite>".encodeToByteArray(), "uuid:x")
        }
        assertThrows(DlnaProtocolException::class.java) {
            parser.parse("<DIDL-Lite><item id=\"1\"><title>${"x".repeat(4097)}</title></item></DIDL-Lite>".encodeToByteArray(), "uuid:x")
        }
        assertThrows(DlnaProtocolException::class.java) {
            parser.parse(ByteArray(2 * 1024 * 1024 + 1), "uuid:x")
        }
    }

    @Test
    fun `DIDL parser caps pages and preserves server UDN plus object ID`() {
        val item = """<container id="c1"><dc:title xmlns:dc="urn:dc">Album</dc:title></container>"""
        val tooMany = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">$item${"<item id=\"x\"/>".repeat(500)}</DIDL-Lite>"""
        assertThrows(DlnaProtocolException::class.java) {
            DidlLiteParser().parse(tooMany.encodeToByteArray(), "uuid:qnap")
        }

        val parsed =
            DidlLiteParser().parse(
                """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="urn:dc">
                  <container id="c1"><dc:title>Album</dc:title></container>
                </DIDL-Lite>
                """.trimIndent().encodeToByteArray(),
                "uuid:qnap",
            )
        assertThat(parsed.single().stableId).isEqualTo("uuid:qnap\u0000c1")
    }

    @Test
    fun `resource selector prefers supported image nearest target then size`() {
        val resources =
            listOf(
                DlnaResource("http://192.168.1.8/a.jpg", "image/jpeg", 1920, 1080, 2_000),
                DlnaResource("http://192.168.1.8/b.png", "image/png", 3840, 2160, 8_000),
                DlnaResource("ftp://192.168.1.8/c.jpg", "image/jpeg", 3840, 2160, 1_000),
                DlnaResource("http://192.168.1.8/d.tiff", "image/tiff", 3840, 2160, 100),
            )

        val selected = DlnaResourceSelector(setOf("image/jpeg", "image/png"), 3840, 2160).select(resources)

        assertThat(selected!!.uri).isEqualTo("http://192.168.1.8/b.png")
    }

    @Test
    fun `ContentDirectory sends v2 BrowseDirectChildren paging request`() = runTest {
        MockWebServer().use { server ->
            val didl =
                """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="urn:dc"><container id="7"><dc:title>Trip</dc:title></container></DIDL-Lite>"""
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:2">
                        <Result>${escape(didl)}</Result><NumberReturned>1</NumberReturned><TotalMatches>3</TotalMatches>
                        </u:BrowseResponse></s:Body></s:Envelope>
                        """.trimIndent(),
                    ),
            )
            val client =
                ContentDirectoryClient(
                    httpClient = OkHttpClient(),
                    controlUrl = server.url("/control").toUri(),
                    serverUdn = "uuid:qnap",
                    version = 2,
                )

            val result = client.browseDirectChildren("0", 2, 1)
            val request = server.takeRequest()
            val requestBody = request.body.readUtf8()

            assertThat(request.headers["SOAPAction"]).contains("ContentDirectory:2#Browse")
            assertThat(requestBody).contains("<StartingIndex>2</StartingIndex>")
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(result.totalMatches).isEqualTo(3)
            assertThat(result.objects.single().objectId).isEqualTo("7")
        }
    }

    @Test
    fun `QNAP missing object SOAP fault requests catalog repair`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(
                        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                            """<s:Fault><detail><UPnPError><errorCode>701</errorCode>""" +
                            """</UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
                    ),
            )
            val client =
                ContentDirectoryClient(
                    OkHttpClient(),
                    server.url("/control").toUri(),
                    "uuid:qnap",
                    1,
                )

            val failure = runCatching { client.browseMetadata("stale-id") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(DlnaObjectMissingException::class.java)
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

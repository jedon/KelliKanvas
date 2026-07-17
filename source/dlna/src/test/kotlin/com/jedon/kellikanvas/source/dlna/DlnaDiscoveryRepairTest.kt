package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class DlnaDiscoveryRepairTest {
    @Test
    fun `setup derives complete profile and rejects mismatched description UDN`() = runTest {
        val wrong = device("uuid:advertised-wrong", "http://192.168.50.10/wrong.xml")
        val qnap = device("uuid:qnap-captured", "http://192.168.50.20:8200/root.xml")
        val discovery =
            DlnaProfileDiscovery(
                discover = { listOf(wrong, qnap) },
                loadDescription = { uri ->
                    if (uri == wrong.location) {
                        fixture("dlna/twonky-description.xml")
                    } else {
                        fixture("dlna/qnap-description.xml")
                    }
                },
            )

        val profiles = discovery.setup(SourceProfileId("profile"))

        assertThat(profiles).hasSize(1)
        assertThat(profiles.single().serverUdn).isEqualTo("uuid:qnap-captured")
        assertThat(profiles.single().descriptionLocation).isEqualTo(qnap.location)
        assertThat(profiles.single().controlUrl).isEqualTo(URI("http://192.168.50.20:8200/base/control/content"))
        assertThat(profiles.single().contentDirectoryVersion).isEqualTo(2)
    }

    @Test
    fun `repair rediscovers moved QNAP by stable UDN`() = runTest {
        val moved = device("uuid:qnap-captured", "http://192.168.50.99:8200/root.xml")
        val discovery =
            DlnaProfileDiscovery(
                discover = { listOf(moved) },
                loadDescription = {
                    fixture("dlna/qnap-description.xml")
                        .decodeToString()
                        .replace("192.168.50.20", "192.168.50.99")
                        .encodeToByteArray()
                },
            )
        val stale =
            DlnaProfile(
                id = SourceProfileId("profile"),
                serverUdn = "uuid:qnap-captured",
                descriptionLocation = URI("http://192.168.50.20:8200/root.xml"),
                controlUrl = URI("http://192.168.50.20:8200/old-control"),
                contentDirectoryVersion = 1,
            )

        val repaired = discovery.repair(stale)

        assertThat(repaired.descriptionLocation).isEqualTo(moved.location)
        assertThat(repaired.controlUrl!!.host).isEqualTo("192.168.50.99")
        assertThat(repaired.contentDirectoryVersion).isEqualTo(2)
        assertThat(repaired.serverUdn).isEqualTo(stale.serverUdn)
    }

    @Test
    fun `captured descriptions scope embedded devices and compatible versions`() {
        val qnap =
            DeviceDescriptionParser().parse(
                fixture("dlna/qnap-description.xml"),
                "http://192.168.50.20:8200/root.xml",
            )
        val twonky =
            DeviceDescriptionParser().parse(
                fixture("dlna/twonky-description.xml"),
                "http://192.168.50.30:9000/desc.xml",
            )

        assertThat(qnap.udn).isEqualTo("uuid:qnap-captured")
        assertThat(qnap.controlUrl.toString()).isEqualTo("http://192.168.50.20:8200/base/control/content")
        assertThat(qnap.version).isEqualTo(2)
        assertThat(twonky.udn).isEqualTo("uuid:twonky-captured")
        assertThat(twonky.controlUrl.toString()).isEqualTo("http://192.168.50.30:9000/rpc/ContentDir")
        assertThat(twonky.version).isEqualTo(1)
    }

    @Test
    fun `description rejects unsafe URLBase`() {
        val xml =
            """<root><URLBase>ftp://192.168.1.2/</URLBase><device>""" +
                """<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>""" +
                """<UDN>uuid:x</UDN><serviceList><service>""" +
                """<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>""" +
                """<controlURL>/control</controlURL></service></serviceList></device></root>"""

        assertThrows(DlnaProtocolException::class.java) {
            DeviceDescriptionParser().parse(xml.encodeToByteArray(), "http://192.168.1.2/root.xml")
        }
    }

    private fun device(
        udn: String,
        location: String,
    ) = SsdpDevice(
        location = URI(location),
        usn = "$udn::urn:schemas-upnp-org:device:MediaServer:1",
        st = "urn:schemas-upnp-org:device:MediaServer:1",
        udn = udn,
    )

    private fun fixture(path: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ).use { it.readBytes() }
}

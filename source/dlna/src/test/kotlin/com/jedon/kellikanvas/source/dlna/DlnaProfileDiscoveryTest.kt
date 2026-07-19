package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.URI

class DlnaProfileDiscoveryTest {
    @Test
    fun `setupNamed keeps friendly name from matching device description`() = runTest {
        val qnap = device("uuid:qnap-representative", "http://192.168.50.20:8200/root.xml")
        val discovery =
            DlnaProfileDiscovery(
                discover = { listOf(qnap) },
                loadDescription = { fixture("dlna/qnap-representative-description.xml") },
            )

        val servers = discovery.setupNamed()

        assertThat(servers).containsExactly(
            DiscoveredDlnaServer(
                friendlyName = "Representative QNAP Media Server",
                profile = discovery.setup().single(),
            ),
        )
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

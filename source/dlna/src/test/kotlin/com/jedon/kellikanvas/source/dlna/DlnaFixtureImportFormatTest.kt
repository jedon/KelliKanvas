package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Properties

class DlnaFixtureImportFormatTest {
    @Test
    fun `fixture index declares provenance and imports parseable descriptions`() {
        val manifests =
            resource("dlna/fixture-index.txt")
                .decodeToString()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

        assertThat(manifests).isNotEmpty()
        manifests.forEach { manifestName ->
            val properties =
                Properties().apply {
                    resource("dlna/$manifestName").inputStream().use(::load)
                }
            assertThat(properties.getProperty("formatVersion")).isEqualTo("1")
            assertThat(properties.getProperty("provenance"))
                .isAnyOf("synthetic-representative", "sanitized-device-capture")
            assertThat(properties.getProperty("vendor")).isNotEmpty()
            val hardwareVerified = properties.getProperty("hardwareVerified").toBooleanStrict()
            assertThat(hardwareVerified)
                .isEqualTo(properties.getProperty("provenance") == "sanitized-device-capture")

            DeviceDescriptionParser().parse(
                resource("dlna/${properties.getProperty("description")}"),
                properties.getProperty("location"),
            )
        }
    }

    private fun resource(path: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ).use { it.readBytes() }
}

package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class UpdateManifestPolicyTest {
    private val validJson =
        UpdateManifest(
            schema = 1,
            sequence = 2,
            packageName = UpdateLimits.PACKAGE_NAME,
            versionCode = 2,
            versionName = "0.2.0",
            apkUrl = URI("http://darklingnas:8088/kellikanvas-2.apk"),
            checksumUrl = URI("http://darklingnas:8088/kellikanvas-2.apk.sha256"),
            sizeBytes = 1234,
            sha256 = "a".repeat(64),
            signerSha256 = "A".repeat(64),
        ).canonicalBytes()

    @Test
    fun `parses schema one manifest`() {
        val manifest = UpdateManifest.parse(validJson)
        assertThat(manifest.versionCode).isEqualTo(2)
        assertThat(manifest.sha256).hasLength(64)
    }

    @Test
    fun `rejects malformed and oversized manifests`() {
        assertThrows(UpdateRejected::class.java) { UpdateManifest.parse("{".toByteArray()) }
        assertThrows(UpdateRejected::class.java) {
            UpdateManifest.parse(ByteArray(UpdateLimits.METADATA_MAX_BYTES + 1))
        }
    }

    @Test
    fun `requires exact package newer version and bounded apk`() {
        val manifest = UpdateManifest.parse(validJson)
        UpdateUrlPolicy.validateManifest(manifest, installedVersionCode = 1)
        assertThrows(UpdateRejected::class.java) {
            UpdateUrlPolicy.validateManifest(manifest.copy(packageName = "other"), 1)
        }
        assertThrows(UpdateRejected::class.java) {
            UpdateUrlPolicy.validateManifest(manifest.copy(versionCode = 1), 1)
        }
        assertThrows(UpdateRejected::class.java) {
            UpdateUrlPolicy.validateManifest(manifest.copy(sizeBytes = UpdateLimits.APK_MAX_BYTES + 1), 1)
        }
    }

    @Test
    fun `accepts only exact private origin`() {
        UpdateUrlPolicy.requireAllowed(URI("http://darklingnas:8088/release.apk"))
        UpdateUrlPolicy.requireAllowed(URI("http://192.168.68.81:8088/release.apk"))
        listOf(
            "https://darklingnas:8088/release.apk",
            "http://darklingnas/release.apk",
            "http://evil:8088/release.apk",
            "http://darklingnas:8088@evil/release.apk",
            "http://darklingnas:8088/release.apk?redirect=http://evil",
            "http://192.168.68.82:8088/release.apk",
        ).forEach { url ->
            assertThrows(url, UpdateRejected::class.java) {
                UpdateUrlPolicy.requireAllowed(URI(url))
            }
        }
    }

    @Test
    fun `cached lan ip origin is accepted only when configured`() {
        val policy = UpdateOriginPolicy.qnapLan(cachedLanIp = "192.168.68.90")
        policy.requireAllowed(URI("http://darklingnas:8088/release.apk"))
        policy.requireAllowed(URI("http://192.168.68.90:8088/release.apk"))
        policy.requireAllowed(URI("http://192.168.68.81:8088/release.apk"))
        assertThrows(UpdateRejected::class.java) {
            policy.requireAllowed(URI("http://192.168.68.91:8088/release.apk"))
        }
        // No cached IP configured keeps the historical two-origin behavior.
        assertThrows(UpdateRejected::class.java) {
            UpdateOriginPolicy.qnapLan().requireAllowed(URI("http://192.168.68.90:8088/release.apk"))
        }
    }

    @Test
    fun `invalid cached lan ips are ignored`() {
        listOf("evil-host", "999.9.9.9", "darklingnas", "192.168.68", " ", null).forEach { cached ->
            val policy = UpdateOriginPolicy.qnapLan(cachedLanIp = cached)
            policy.requireAllowed(URI("http://darklingnas:8088/release.apk"))
            policy.requireAllowed(URI("http://192.168.68.81:8088/release.apk"))
            assertThrows("cached=$cached", UpdateRejected::class.java) {
                policy.requireAllowed(URI("http://evil-host:8088/release.apk"))
            }
            assertThat(UpdateOriginPolicy.qnapControlUris(cached))
                .isEqualTo(UpdateOriginPolicy.CONTROL_URIS)
        }
    }

    @Test
    fun `control uris are ordered hostname then cached ip then static ip`() {
        assertThat(UpdateOriginPolicy.qnapControlUris("192.168.68.90"))
            .containsExactly(
                URI("http://darklingnas:8088/update-envelope.json"),
                URI("http://192.168.68.90:8088/update-envelope.json"),
                URI("http://192.168.68.81:8088/update-envelope.json"),
            )
            .inOrder()
        assertThat(UpdateOriginPolicy.qnapControlUris())
            .containsExactly(
                URI("http://darklingnas:8088/update-envelope.json"),
                URI("http://192.168.68.81:8088/update-envelope.json"),
            )
            .inOrder()
        // A cached value equal to the static default must not duplicate entries.
        assertThat(UpdateOriginPolicy.qnapControlUris("192.168.68.81"))
            .isEqualTo(UpdateOriginPolicy.CONTROL_URIS)
    }

    @Test
    fun `remote origins require explicit https host`() {
        val policy = UpdateOriginPolicy.remoteHttps("updates.example.test")
        policy.requireAllowed(URI("https://updates.example.test/release.apk"))
        assertThrows(UpdateRejected::class.java) {
            policy.requireAllowed(URI("http://updates.example.test/release.apk"))
        }
        assertThrows(UpdateRejected::class.java) {
            policy.requireAllowed(URI("https://other.example.test/release.apk"))
        }
    }

    @Test
    fun `automatic checks require more than 24 hours while manual always checks`() {
        assertThat(UpdateCheckPolicy.shouldCheck(manual = true, nowMillis = 1, lastCheckMillis = 1)).isTrue()
        assertThat(UpdateCheckPolicy.shouldCheck(manual = false, nowMillis = UpdateLimits.CHECK_INTERVAL_MILLIS, lastCheckMillis = 0)).isFalse()
        assertThat(UpdateCheckPolicy.shouldCheck(manual = false, nowMillis = UpdateLimits.CHECK_INTERVAL_MILLIS + 1, lastCheckMillis = 0)).isTrue()
    }
}

package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SignedManifestTest {
    private val keyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(256)
        }.generateKeyPair()
    private val authenticator = ManifestAuthenticator(keyPair.public)

    @Test
    fun `accepts only signed canonical schema bytes`() {
        val bytes = manifest().canonicalBytes()
        assertThat(authenticator.authenticate(bytes, sign(bytes))).isEqualTo(manifest())
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticate(bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }, sign(bytes))
        }
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticate(bytes.dropLast(1).toByteArray(), sign(bytes.dropLast(1).toByteArray()))
        }
    }

    @Test
    fun `rejects duplicate unknown and cross-type fields`() {
        val valid = manifest().canonicalBytes().toString(Charsets.UTF_8)
        listOf(
            valid.replace("\"schema\":1", "\"schema\":1,\"schema\":\"1\""),
            valid.replace("\"schema\":1", "\"extra\":1,\"schema\":1"),
            valid.replace("\"schema\":1", "\"schema\":\"1\""),
        ).forEach { malformed ->
            val bytes = malformed.toByteArray()
            assertThrows(UpdateRejected::class.java) {
                authenticator.authenticate(bytes, sign(bytes))
            }
        }
    }

    @Test
    fun `bounds authenticated replay while allowing fresh install`() {
        val store = InMemoryAuthenticatedReleaseStore()
        val guard = ReleaseReplayGuard(store)
        guard.accept(manifest(sequence = 10, versionCode = 20))
        assertThat(store.highest()).isEqualTo(AuthenticatedRelease(10, 20))
        assertThrows(UpdateRejected::class.java) {
            guard.accept(manifest(sequence = 9, versionCode = 21))
        }
        assertThrows(UpdateRejected::class.java) {
            guard.accept(manifest(sequence = 11, versionCode = 19))
        }
        ReleaseReplayGuard(InMemoryAuthenticatedReleaseStore()).accept(manifest(sequence = 1, versionCode = 1))
    }

    @Test
    fun `bridge release can pin current and next metadata keys`() {
        val next =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(256)
            }.generateKeyPair()
        val pins =
            listOf(keyPair.public, next.public).joinToString(",") {
                Base64.getEncoder().encodeToString(it.encoded)
            }
        val bytes = manifest().canonicalBytes()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(next.private)
                update(bytes)
                sign()
            }
        assertThat(ManifestAuthenticator.fromPinnedBase64(pins).authenticate(bytes, signature))
            .isEqualTo(manifest())
    }

    private fun manifest(sequence: Long = 7, versionCode: Long = 2): UpdateManifest = UpdateManifest(
        schema = 1,
        sequence = sequence,
        packageName = UpdateLimits.PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "0.2.0",
        apkUrl = java.net.URI("http://darklingnas:8088/kellikanvas-$versionCode.apk"),
        checksumUrl = java.net.URI("http://darklingnas:8088/kellikanvas-$versionCode.apk.sha256"),
        sizeBytes = 10,
        sha256 = "a".repeat(64),
        signerSha256 = "B".repeat(64),
    )

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(bytes)
        sign()
    }
}

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
    private val authenticator = ManifestAuthenticator(mapOf("release-v1" to keyPair.public))

    @Test
    fun `accepts only signed canonical schema bytes`() {
        val bytes = manifest().canonicalBytes()
        val envelope = envelope(bytes, sign(bytes))
        assertThat(authenticator.authenticateEnvelope(envelope).manifest).isEqualTo(manifest())
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticateEnvelope(
                envelope(bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }, sign(bytes)),
            )
        }
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticateEnvelope(
                envelope(bytes.dropLast(1).toByteArray(), sign(bytes.dropLast(1).toByteArray())),
            )
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
                authenticator.authenticateEnvelope(envelope(bytes, sign(bytes)))
            }
        }
    }

    @Test
    fun `bounds authenticated replay while allowing fresh install`() {
        val store = InMemoryAuthenticatedReleaseStore()
        val guard = ReleaseReplayGuard(store)
        assertThat(guard.accept(manifest(sequence = 10, versionCode = 20), "hash-a"))
            .isEqualTo(ReplayDecision.NEW_RELEASE)
        assertThat(store.highest()).isEqualTo(AuthenticatedRelease(10, 20, "hash-a"))
        assertThat(guard.accept(manifest(sequence = 10, versionCode = 20), "hash-a"))
            .isEqualTo(ReplayDecision.IDEMPOTENT_RETRY)
        assertThrows(UpdateRejected::class.java) {
            guard.accept(manifest(sequence = 10, versionCode = 20), "conflicting-hash")
        }
        assertThrows(UpdateRejected::class.java) {
            guard.accept(manifest(sequence = 9, versionCode = 21), "hash-b")
        }
        ReleaseReplayGuard(InMemoryAuthenticatedReleaseStore()).accept(
            manifest(sequence = 1, versionCode = 1),
            "fresh",
        )
    }

    @Test
    fun `bridge release can pin current and next metadata keys`() {
        val next =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(256)
            }.generateKeyPair()
        val pins =
            "current=${Base64.getEncoder().encodeToString(keyPair.public.encoded)}," +
                "next=${Base64.getEncoder().encodeToString(next.public.encoded)}"
        val bytes = manifest().canonicalBytes()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(next.private)
                update(bytes)
                sign()
            }
        assertThat(
            ManifestAuthenticator.fromPinnedBase64(pins)
                .authenticateEnvelope(envelope(bytes, signature, "next"))
                .manifest,
        ).isEqualTo(manifest())
    }

    @Test
    fun `malformed DER and unknown key are privacy safe rejection`() {
        val bytes = manifest().canonicalBytes()
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticateEnvelope(envelope(bytes, byteArrayOf(1, 2, 3)))
        }
        assertThrows(UpdateRejected::class.java) {
            authenticator.authenticateEnvelope(envelope(bytes, sign(bytes), "unknown"))
        }
    }

    @Test
    fun `rejects unknown duplicate and noncanonical envelope fields`() {
        val bytes = manifest().canonicalBytes()
        val valid = envelope(bytes, sign(bytes)).toString(Charsets.UTF_8)
        listOf(
            valid.replace("\"keyId\":", "\"extra\":1,\"keyId\":"),
            valid.replace("\"keyId\":\"release-v1\"", "\"keyId\":\"release-v1\",\"keyId\":\"release-v1\""),
            valid.replace(",\"keyId\"", ", \"keyId\""),
        ).forEach { malformed ->
            assertThrows(UpdateRejected::class.java) {
                authenticator.authenticateEnvelope(malformed.toByteArray())
            }
        }
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

    private fun envelope(
        payload: ByteArray,
        signature: ByteArray,
        keyId: String = "release-v1",
    ): ByteArray {
        val payloadBase64 = Base64.getEncoder().encodeToString(payload)
        val signatureBase64 = Base64.getEncoder().encodeToString(signature)
        return (
            """{"envelopeSchema":1,"keyId":"$keyId","payload":"$payloadBase64","signature":"$signatureBase64"}""" +
                "\n"
            ).toByteArray()
    }
}

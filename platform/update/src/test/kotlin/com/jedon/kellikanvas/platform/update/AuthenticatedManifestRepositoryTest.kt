package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class AuthenticatedManifestRepositoryTest {
    private val keyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(256)
        }.generateKeyPair()
    private val authenticator = ManifestAuthenticator(mapOf("release-v1" to keyPair.public))
    private val now = 1_700_000_000_000L

    @Test
    fun `failed fetch does not record check timestamp`() {
        val timestamps = RecordingTimestampStore()
        val repository = repository(FailingTransport(IOException("NAS unreachable")), timestamps)

        assertThrows(IOException::class.java) {
            repository.check(manual = true, installedVersionCode = 1)
        }

        assertThat(timestamps.recorded).isEmpty()
    }

    @Test
    fun `successful check records timestamp after authentication`() {
        val timestamps = RecordingTimestampStore()
        val envelope = signedEnvelope(manifest())
        val repository = repository(FixedTransport(envelope), timestamps)

        val result = repository.check(manual = true, installedVersionCode = 1)

        assertThat(result).isEqualTo(manifest())
        assertThat(timestamps.recorded).containsExactly(now)
    }

    @Test
    fun `failed auth does not record check timestamp`() {
        val timestamps = RecordingTimestampStore()
        val payload = manifest().canonicalBytes()
        val envelope = envelope(payload, byteArrayOf(1, 2, 3))
        val repository = repository(FixedTransport(envelope), timestamps)

        assertThrows(UpdateRejected::class.java) {
            repository.check(manual = true, installedVersionCode = 1)
        }

        assertThat(timestamps.recorded).isEmpty()
    }

    @Test
    fun `successful auth of non-newer update records timestamp and returns null`() {
        val timestamps = RecordingTimestampStore()
        val current = manifest(versionCode = 2)
        val repository = repository(FixedTransport(signedEnvelope(current)), timestamps)

        val result = repository.check(manual = true, installedVersionCode = 2)

        assertThat(result).isNull()
        assertThat(timestamps.recorded).containsExactly(now)
    }

    private fun repository(
        transport: UpdateTransport,
        timestamps: CheckTimestampStore,
    ): AuthenticatedManifestRepository = AuthenticatedManifestRepository(
        transport = transport,
        authenticator = authenticator,
        replayGuard = ReleaseReplayGuard(InMemoryAuthenticatedReleaseStore()),
        timestampStore = timestamps,
        nowMillis = { now },
    )

    private fun manifest(
        sequence: Long = 7,
        versionCode: Long = 2,
    ): UpdateManifest = UpdateManifest(
        schema = 1,
        sequence = sequence,
        packageName = UpdateLimits.PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "0.2.0",
        apkUrl = URI("http://darklingnas:8088/kellikanvas-$versionCode.apk"),
        checksumUrl = URI("http://darklingnas:8088/kellikanvas-$versionCode.apk.sha256"),
        sizeBytes = 10,
        sha256 = "a".repeat(64),
        signerSha256 = "B".repeat(64),
    )

    private fun signedEnvelope(manifest: UpdateManifest): ByteArray {
        val payload = manifest.canonicalBytes()
        return envelope(payload, sign(payload))
    }

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

    private class RecordingTimestampStore(
        private var last: Long? = null,
    ) : CheckTimestampStore {
        val recorded = mutableListOf<Long>()

        override fun lastCheckMillis(): Long? = last

        override fun recordCheck(timestampMillis: Long) {
            recorded += timestampMillis
            last = timestampMillis
        }
    }

    private class FixedTransport(
        private val body: ByteArray,
        private val statusCode: Int = 200,
    ) : UpdateTransport {
        override fun open(url: URI, maxBytes: Long): UpdateResponse = UpdateResponse(
            statusCode = statusCode,
            finalUrl = url,
            redirected = false,
            contentLength = body.size.toLong(),
            body = ByteArrayInputStream(body),
        )
    }

    private class FailingTransport(
        private val error: Exception,
    ) : UpdateTransport {
        override fun open(url: URI, maxBytes: Long): UpdateResponse = throw error
    }
}

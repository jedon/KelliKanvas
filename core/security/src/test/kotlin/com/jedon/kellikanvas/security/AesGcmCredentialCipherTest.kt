package com.jedon.kellikanvas.security

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class AesGcmCredentialCipherTest {
    private val cipher = AesGcmCredentialCipher()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val profileId = SourceProfileId("profile-one")

    @Test
    fun roundTripReturnsOriginalSecret() {
        val secret = "not-persisted".encodeToByteArray()

        val encrypted = cipher.encrypt(key, profileId, secret)
        val decrypted = cipher.decrypt(key, profileId, encrypted)

        assertThat(decrypted).isEqualTo(secret)
        secret.fill(0)
        decrypted.fill(0)
    }

    @Test
    fun differentProfileCannotDecrypt() {
        val secret = byteArrayOf(1, 2, 3, 4)
        val encrypted = cipher.encrypt(key, profileId, secret)

        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(key, SourceProfileId("profile-two"), encrypted)
        }
        secret.fill(0)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val secret = byteArrayOf(5, 6, 7, 8)
        val encrypted = cipher.encrypt(key, profileId, secret)
        val tampered =
            encrypted.copy(
                ciphertext = encrypted.ciphertext.copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                },
            )

        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(key, profileId, tampered)
        }
        secret.fill(0)
    }

    @Test
    fun encryptionUsesUniqueTwelveByteIvs() {
        val secret = byteArrayOf(9, 10, 11)

        val first = cipher.encrypt(key, profileId, secret)
        val second = cipher.encrypt(key, profileId, secret)

        assertThat(first.iv).hasLength(12)
        assertThat(second.iv).hasLength(12)
        assertThat(first.iv).isNotEqualTo(second.iv)
        secret.fill(0)
    }

    @Test
    fun encryptionUsesProviderGeneratedIv() {
        val session = RecordingCipherSession()
        val providerGeneratedIv = ByteArray(12) { (it + 1).toByte() }
        session.generatedIv = providerGeneratedIv
        val cipher = AesGcmCredentialCipher(CipherSessionFactory { session })
        val secret = byteArrayOf(12, 13, 14)

        val encrypted = cipher.encrypt(key, profileId, secret)

        assertThat(session.encryptInitCount).isEqualTo(1)
        assertThat(encrypted.iv).isEqualTo(providerGeneratedIv)
        secret.fill(0)
    }

    @Test
    fun providerFailureDoesNotExposeProviderDetails() {
        val session = RecordingCipherSession()
        session.failure = ProviderException("sensitive provider detail")
        val cipher = AesGcmCredentialCipher(CipherSessionFactory { session })

        val failure =
            assertThrows(CredentialCipherUnavailableException::class.java) {
                cipher.encrypt(key, profileId, byteArrayOf(15, 16, 17))
            }

        assertThat(failure).hasMessageThat().isEqualTo("Credential cipher is temporarily unavailable")
        assertThat(failure.cause).isNull()
    }

    @Test
    fun profileAadIntermediateIsZeroed() {
        val session = RecordingCipherSession()
        val cipher = AesGcmCredentialCipher(CipherSessionFactory { session })

        cipher.encrypt(key, profileId, byteArrayOf(18, 19, 20))

        assertThat(checkNotNull(session.aad).all { it == 0.toByte() }).isTrue()
    }

    private class RecordingCipherSession : CipherSession {
        var generatedIv = ByteArray(12)
        var encryptInitCount = 0
        var failure: RuntimeException? = null
        var aad: ByteArray? = null

        override val iv: ByteArray
            get() = generatedIv.copyOf()

        override fun initForEncryption(key: SecretKey) {
            encryptInitCount += 1
        }

        override fun initForDecryption(
            key: SecretKey,
            iv: ByteArray,
        ) = Unit

        override fun updateAad(aad: ByteArray) {
            this.aad = aad
        }

        override fun doFinal(input: ByteArray): ByteArray {
            failure?.let { throw it }
            return input.copyOf()
        }
    }
}

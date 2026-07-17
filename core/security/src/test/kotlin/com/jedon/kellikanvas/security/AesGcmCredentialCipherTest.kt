package com.jedon.kellikanvas.security

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
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
}

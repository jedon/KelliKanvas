package com.jedon.kellikanvas.security

import com.jedon.kellikanvas.model.SourceProfileId
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AesGcmCredentialCipher(
    private val secureRandom: SecureRandom = SecureRandom(),
) : CredentialCipher {
    override fun encrypt(
        key: SecretKey,
        profileId: SourceProfileId,
        plaintext: ByteArray,
    ): EncryptedCredential {
        require(plaintext.isNotEmpty()) { "Credential must not be empty" }
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val aad = profileId.value.encodeToByteArray()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.updateAAD(aad)
            EncryptedCredential(iv.copyOf(), cipher.doFinal(plaintext))
        } finally {
            aad.fill(0)
        }
    }

    override fun decrypt(
        key: SecretKey,
        profileId: SourceProfileId,
        encrypted: EncryptedCredential,
    ): ByteArray {
        val aad = profileId.value.encodeToByteArray()
        val iv = encrypted.iv.copyOf()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_SIZE_BITS, iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted.ciphertext)
        } finally {
            aad.fill(0)
            iv.fill(0)
        }
    }

    companion object {
        internal const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

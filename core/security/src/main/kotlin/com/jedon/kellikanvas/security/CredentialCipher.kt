package com.jedon.kellikanvas.security

import com.jedon.kellikanvas.model.SourceProfileId
import javax.crypto.SecretKey

class EncryptedCredential(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(iv.size == AesGcmCredentialCipher.IV_SIZE_BYTES) { "AES-GCM IV must be 12 bytes" }
        require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
    }

    fun copy(
        iv: ByteArray = this.iv.copyOf(),
        ciphertext: ByteArray = this.ciphertext.copyOf(),
    ): EncryptedCredential = EncryptedCredential(iv.copyOf(), ciphertext.copyOf())

    override fun equals(other: Any?): Boolean = other is EncryptedCredential &&
        iv.contentEquals(other.iv) &&
        ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()

    override fun toString(): String = "EncryptedCredential(<redacted>)"
}

interface CredentialCipher {
    fun encrypt(
        key: SecretKey,
        profileId: SourceProfileId,
        plaintext: ByteArray,
    ): EncryptedCredential

    fun decrypt(
        key: SecretKey,
        profileId: SourceProfileId,
        encrypted: EncryptedCredential,
    ): ByteArray
}

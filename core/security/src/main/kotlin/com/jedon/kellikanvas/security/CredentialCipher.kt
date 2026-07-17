package com.jedon.kellikanvas.security

import com.jedon.kellikanvas.model.SourceProfileId
import java.security.GeneralSecurityException
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

internal fun interface CipherSessionFactory {
    fun create(): CipherSession
}

internal interface CipherSession {
    val iv: ByteArray

    fun initForEncryption(key: SecretKey)

    fun initForDecryption(
        key: SecretKey,
        iv: ByteArray,
    )

    fun updateAad(aad: ByteArray)

    fun doFinal(input: ByteArray): ByteArray
}

class CredentialCipherCorruptedException : GeneralSecurityException("Encrypted credential is unusable")

class CredentialCipherKeyInvalidatedException : GeneralSecurityException("Credential key is invalidated")

class CredentialCipherUnavailableException : GeneralSecurityException("Credential cipher is temporarily unavailable")

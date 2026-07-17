package com.jedon.kellikanvas.security

import com.jedon.kellikanvas.model.SourceProfileId
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AesGcmCredentialCipher internal constructor(
    private val sessionFactory: CipherSessionFactory,
) : CredentialCipher {
    constructor() : this(CipherSessionFactory { JcaCipherSession() })

    override fun encrypt(
        key: SecretKey,
        profileId: SourceProfileId,
        plaintext: ByteArray,
    ): EncryptedCredential {
        require(plaintext.isNotEmpty()) { "Credential must not be empty" }
        val aad = profileId.value.encodeToByteArray()
        return try {
            val session = sessionFactory.create()
            session.initForEncryption(key)
            session.updateAad(aad)
            EncryptedCredential(session.iv, session.doFinal(plaintext))
        } catch (exception: CredentialCipherKeyInvalidatedException) {
            throw exception
        } catch (exception: GeneralSecurityException) {
            if (exception.isPermanentKeyInvalidation()) {
                throw CredentialCipherKeyInvalidatedException()
            }
            throw CredentialCipherUnavailableException()
        } catch (_: ProviderException) {
            throw CredentialCipherUnavailableException()
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
            val session = sessionFactory.create()
            session.initForDecryption(key, iv)
            session.updateAad(aad)
            session.doFinal(encrypted.ciphertext)
        } catch (exception: CredentialCipherKeyInvalidatedException) {
            throw exception
        } catch (exception: CredentialCipherCorruptedException) {
            throw exception
        } catch (_: AEADBadTagException) {
            throw CredentialCipherCorruptedException()
        } catch (_: BadPaddingException) {
            throw CredentialCipherCorruptedException()
        } catch (exception: GeneralSecurityException) {
            if (exception.isPermanentKeyInvalidation()) {
                throw CredentialCipherKeyInvalidatedException()
            }
            throw CredentialCipherUnavailableException()
        } catch (_: ProviderException) {
            throw CredentialCipherUnavailableException()
        } finally {
            aad.fill(0)
            iv.fill(0)
        }
    }

    private class JcaCipherSession : CipherSession {
        private val cipher = Cipher.getInstance(TRANSFORMATION)

        override val iv: ByteArray
            get() = cipher.iv?.copyOf() ?: throw CredentialCipherUnavailableException()

        override fun initForEncryption(key: SecretKey) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }

        override fun initForDecryption(
            key: SecretKey,
            iv: ByteArray,
        ) {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        }

        override fun updateAad(aad: ByteArray) {
            cipher.updateAAD(aad)
        }

        override fun doFinal(input: ByteArray): ByteArray = cipher.doFinal(input)
    }

    companion object {
        internal const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private fun Throwable.isPermanentKeyInvalidation(): Boolean = generateSequence(this) { it.cause }
    .any { it.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException" }

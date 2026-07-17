package com.jedon.kellikanvas.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface CredentialKeyProvider {
    fun get(): SecretKey?

    fun getOrCreate(): SecretKey

    fun delete()
}

class CredentialKeyInvalidatedException : RuntimeException("Credential key is invalidated")

class CredentialKeyAccessException : RuntimeException("Credential key is temporarily unavailable")

internal interface AndroidKeyStoreAccess {
    fun get(): SecretKey?

    fun generate(): SecretKey

    fun delete()
}

class AndroidKeystoreKeyProvider internal constructor(
    private val access: AndroidKeyStoreAccess,
) : CredentialKeyProvider {
    constructor() : this(PlatformAndroidKeyStoreAccess())

    override fun get(): SecretKey? = synchronized(ALIAS_LOCK) {
        access.get()
    }

    override fun getOrCreate(): SecretKey = synchronized(ALIAS_LOCK) {
        access.get()?.let { return@synchronized it }
        access.generate()
        access.get() ?: throw CredentialKeyAccessException()
    }

    override fun delete() {
        synchronized(ALIAS_LOCK) {
            access.delete()
        }
    }

    private class PlatformAndroidKeyStoreAccess : AndroidKeyStoreAccess {
        override fun get(): SecretKey? = classifyKeyAccess {
            keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        }

        override fun generate(): SecretKey = classifyKeyAccess {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }

        override fun delete() {
            classifyKeyAccess {
                keyStore().deleteEntry(KEY_ALIAS)
            }
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        private fun <T> classifyKeyAccess(block: () -> T): T = try {
            block()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw CredentialKeyInvalidatedException()
        } catch (_: UnrecoverableKeyException) {
            throw CredentialKeyInvalidatedException()
        } catch (_: ProviderException) {
            throw CredentialKeyAccessException()
        } catch (_: Exception) {
            throw CredentialKeyAccessException()
        }
    }

    companion object {
        const val KEY_ALIAS = "kellikanvas.source-credentials.v1"
        private val ALIAS_LOCK = Any()
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}

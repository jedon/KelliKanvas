package com.jedon.kellikanvas.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface CredentialKeyProvider {
    fun get(): SecretKey?

    fun getOrCreate(): SecretKey
}

class CredentialKeyUnavailableException(
    cause: Throwable? = null,
) : RuntimeException("Credential key is unavailable", cause)

class AndroidKeystoreKeyProvider : CredentialKeyProvider {
    override fun get(): SecretKey? = try {
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey
    } catch (exception: Exception) {
        throw CredentialKeyUnavailableException(exception)
    }

    override fun getOrCreate(): SecretKey = get()
        ?: try {
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
        } catch (exception: Exception) {
            throw CredentialKeyUnavailableException(exception)
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    companion object {
        const val KEY_ALIAS = "kellikanvas.source-credentials.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}

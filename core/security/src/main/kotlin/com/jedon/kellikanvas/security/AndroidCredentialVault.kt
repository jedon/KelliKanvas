package com.jedon.kellikanvas.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.jedon.kellikanvas.model.SourceProfileId
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest

class AndroidCredentialVault(
    context: Context,
    private val keyProvider: CredentialKeyProvider = AndroidKeystoreKeyProvider(),
    private val cipher: CredentialCipher = AesGcmCredentialCipher(),
) : CredentialVault {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun write(
        profileId: SourceProfileId,
        secret: ByteArray,
    ) {
        require(secret.isNotEmpty()) { "Credential must not be empty" }
        val temporarySecret = secret.copyOf()
        var encrypted: EncryptedCredential? = null
        try {
            encrypted = cipher.encrypt(keyProvider.getOrCreate(), profileId, temporarySecret)
            preferences
                .edit()
                .putString(storageKey(profileId), encode(encrypted))
                .commit()
                .also { check(it) { "Unable to persist encrypted credential" } }
        } finally {
            temporarySecret.fill(0)
            encrypted?.iv?.fill(0)
            encrypted?.ciphertext?.fill(0)
        }
    }

    override fun write(
        profileId: SourceProfileId,
        secret: CharArray,
    ) {
        require(secret.isNotEmpty()) { "Credential must not be empty" }
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret))
        val temporarySecret = ByteArray(encoded.remaining())
        encoded.get(temporarySecret)
        if (encoded.hasArray()) {
            encoded.array().fill(0)
        }
        try {
            write(profileId, temporarySecret)
        } finally {
            temporarySecret.fill(0)
        }
    }

    override fun read(profileId: SourceProfileId): CredentialReadResult {
        val preferenceKey = storageKey(profileId)
        val stored = preferences.getString(preferenceKey, null) ?: return CredentialReadResult.Missing
        var encrypted: EncryptedCredential? = null
        var plaintext: ByteArray? = null
        return try {
            val key = keyProvider.get() ?: return requiresReentry(preferenceKey)
            val decoded = decode(stored)
            encrypted = decoded
            val decrypted = cipher.decrypt(key, profileId, decoded)
            plaintext = decrypted
            CredentialReadResult.Present(CredentialSecret(decrypted))
        } catch (_: CredentialKeyUnavailableException) {
            requiresReentry(preferenceKey)
        } catch (_: GeneralSecurityException) {
            requiresReentry(preferenceKey)
        } catch (_: IllegalArgumentException) {
            requiresReentry(preferenceKey)
        } finally {
            encrypted?.iv?.fill(0)
            encrypted?.ciphertext?.fill(0)
            plaintext?.fill(0)
        }
    }

    override fun remove(profileId: SourceProfileId) {
        check(preferences.edit().remove(storageKey(profileId)).commit()) {
            "Unable to remove encrypted credential"
        }
    }

    private fun requiresReentry(preferenceKey: String): CredentialReadResult {
        check(preferences.edit().remove(preferenceKey).commit()) {
            "Unable to remove unusable encrypted credential"
        }
        return CredentialReadResult.RequiresReentry
    }

    private fun storageKey(profileId: SourceProfileId): String {
        val raw = profileId.value.encodeToByteArray()
        var digest: ByteArray? = null
        return try {
            val computedDigest = MessageDigest.getInstance("SHA-256").digest(raw)
            digest = computedDigest
            Base64.encodeToString(computedDigest, Base64.NO_WRAP or Base64.URL_SAFE)
        } finally {
            raw.fill(0)
            digest?.fill(0)
        }
    }

    private fun encode(encrypted: EncryptedCredential): String = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP) +
        SEPARATOR +
        Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)

    private fun decode(stored: String): EncryptedCredential {
        val separatorIndex = stored.indexOf(SEPARATOR)
        require(separatorIndex > 0 && separatorIndex < stored.lastIndex) {
            "Invalid encrypted credential"
        }
        return EncryptedCredential(
            iv = Base64.decode(stored.substring(0, separatorIndex), Base64.NO_WRAP),
            ciphertext = Base64.decode(stored.substring(separatorIndex + 1), Base64.NO_WRAP),
        )
    }

    companion object {
        const val PREFERENCES_NAME = "kellikanvas_source_credentials"
        private const val SEPARATOR = '.'
    }
}

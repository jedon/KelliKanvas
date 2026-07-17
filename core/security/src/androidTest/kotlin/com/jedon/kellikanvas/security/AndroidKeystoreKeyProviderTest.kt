package com.jedon.kellikanvas.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyProviderTest {
    private val provider = AndroidKeystoreKeyProvider()

    @Before
    @After
    fun removeTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(AndroidKeystoreKeyProvider.KEY_ALIAS)
        }
    }

    @Test
    fun repeatedLookupReturnsTheSameUsableKey() {
        val cipher = AesGcmCredentialCipher()
        val profileId = SourceProfileId("instrumented-profile")
        val plaintext = byteArrayOf(42, 43, 44)

        val encrypted = cipher.encrypt(provider.getOrCreate(), profileId, plaintext)
        val decrypted = cipher.decrypt(provider.getOrCreate(), profileId, encrypted)

        assertThat(decrypted).isEqualTo(plaintext)
        plaintext.fill(0)
        decrypted.fill(0)
    }
}

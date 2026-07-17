package com.jedon.kellikanvas.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class AndroidCredentialVaultTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val profileId = SourceProfileId("living-room-nas")

    @Test
    fun removeDeletesStoredCredential() {
        val vault = vaultWith(TestKeyProvider())
        val secret = charArrayOf('s', '3', 'c', 'r', '3', 't')
        vault.write(profileId, secret)
        secret.fill('\u0000')

        vault.remove(profileId)

        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.Missing)
    }

    @Test
    fun presentSecretIsDefensiveAndClosable() {
        val vault = vaultWith(TestKeyProvider())
        val source = byteArrayOf(20, 21, 22)
        vault.write(profileId, source)
        source.fill(0)

        val result = vault.read(profileId) as CredentialReadResult.Present
        val first = result.secret.copyBytes()
        first.fill(0)
        val second = result.secret.copyBytes()

        assertThat(second).isEqualTo(byteArrayOf(20, 21, 22))
        result.close()
        assertThat(result.secret.isClosed).isTrue()
        second.fill(0)
    }

    @Test
    fun missingKeyDeletesCiphertextAndRequiresReentry() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(1, 2, 3))
        keyProvider.key = null

        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.RequiresReentry)
        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.Missing)
    }

    @Test
    fun invalidatedKeyDeletesCiphertextAndRequiresReentry() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(4, 5, 6))
        keyProvider.failure = CredentialKeyUnavailableException()

        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.RequiresReentry)
        keyProvider.failure = null
        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.Missing)
    }

    private fun vaultWith(keyProvider: TestKeyProvider): AndroidCredentialVault = AndroidCredentialVault(
        context,
        keyProvider,
        AesGcmCredentialCipher(),
    )

    private class TestKeyProvider : CredentialKeyProvider {
        var key: SecretKey? =
            KeyGenerator.getInstance("AES").apply {
                init(256)
            }.generateKey()
        var failure: RuntimeException? = null

        override fun get(): SecretKey? {
            failure?.let { throw it }
            return key
        }

        override fun getOrCreate(): SecretKey {
            failure?.let { throw it }
            return key
                ?: KeyGenerator.getInstance("AES").apply {
                    init(256)
                }.generateKey().also { key = it }
        }
    }
}

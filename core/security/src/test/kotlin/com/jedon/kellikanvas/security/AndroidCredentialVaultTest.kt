package com.jedon.kellikanvas.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class AndroidCredentialVaultTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val profileId = SourceProfileId("living-room-nas")

    @Before
    fun clearCredentials() {
        context
            .getSharedPreferences(AndroidCredentialVault.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

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
    fun invalidatedKeyAllowsReplacementWriteAndRead() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(4, 5, 6))
        keyProvider.failure = CredentialKeyInvalidatedException()

        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.RequiresReentry)
        assertThat(keyProvider.deleteCount).isEqualTo(1)

        vault.write(profileId, byteArrayOf(7, 8, 9))

        assertPresent(vault.read(profileId), byteArrayOf(7, 8, 9))
    }

    @Test
    fun writeRotatesInvalidatedKeyAndRetries() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(25, 26, 27))
        keyProvider.failure = CredentialKeyInvalidatedException()

        vault.write(profileId, byteArrayOf(28, 29, 30))

        assertThat(keyProvider.deleteCount).isEqualTo(1)
        assertPresent(vault.read(profileId), byteArrayOf(28, 29, 30))
    }

    @Test
    fun transientKeyFailurePreservesCiphertextForRetry() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(10, 11, 12))
        keyProvider.failure = CredentialKeyAccessException()

        val failure =
            org.junit.Assert.assertThrows(CredentialVaultUnavailableException::class.java) {
                vault.read(profileId)
            }

        assertThat(failure).hasMessageThat().isEqualTo("Credential vault temporarily unavailable")
        assertThat(failure.cause).isNull()
        keyProvider.failure = null
        assertPresent(vault.read(profileId), byteArrayOf(10, 11, 12))
    }

    @Test
    fun transientWriteFailurePreservesExistingCiphertext() {
        val keyProvider = TestKeyProvider()
        val vault = vaultWith(keyProvider)
        vault.write(profileId, byteArrayOf(31, 32, 33))
        keyProvider.failure = CredentialKeyAccessException()

        org.junit.Assert.assertThrows(CredentialVaultUnavailableException::class.java) {
            vault.write(profileId, byteArrayOf(34, 35, 36))
        }

        keyProvider.failure = null
        assertPresent(vault.read(profileId), byteArrayOf(31, 32, 33))
    }

    @Test
    fun corruptCiphertextIsDeletedAndReplacementSucceeds() {
        val vault = vaultWith(TestKeyProvider())
        vault.write(profileId, byteArrayOf(13, 14, 15))
        val preferences =
            context.getSharedPreferences(AndroidCredentialVault.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val key = preferences.all.keys.single()
        val stored = checkNotNull(preferences.getString(key, null))
        val ciphertextStart = stored.indexOf('.') + 1
        val tampered =
            stored.replaceRange(
                ciphertextStart,
                ciphertextStart + 1,
                if (stored[ciphertextStart] == 'A') "B" else "A",
            )
        preferences.edit().putString(key, tampered).commit()

        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.RequiresReentry)
        assertThat(vault.read(profileId)).isEqualTo(CredentialReadResult.Missing)

        vault.write(profileId, byteArrayOf(16, 17, 18))
        assertPresent(vault.read(profileId), byteArrayOf(16, 17, 18))
    }

    @Test
    fun staleFailedReadCannotDeleteConcurrentReplacement() {
        val keyProvider = TestKeyProvider()
        val blockingCipher = BlockingCorruptCipher(AesGcmCredentialCipher())
        val vault = AndroidCredentialVault(context, keyProvider, blockingCipher)
        vault.write(profileId, byteArrayOf(19, 20, 21))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val read = executor.submit<CredentialReadResult> { vault.read(profileId) }
            assertThat(blockingCipher.decryptStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val writeStarted = CountDownLatch(1)
            val write =
                executor.submit {
                    writeStarted.countDown()
                    vault.write(profileId, byteArrayOf(22, 23, 24))
                }
            assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(100)
            blockingCipher.allowDecrypt.countDown()

            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(CredentialReadResult.RequiresReentry)
            write.get(5, TimeUnit.SECONDS)
            assertPresent(vault.read(profileId), byteArrayOf(22, 23, 24))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun vaultWith(keyProvider: TestKeyProvider): AndroidCredentialVault = AndroidCredentialVault(
        context,
        keyProvider,
        AesGcmCredentialCipher(),
    )

    private fun assertPresent(
        result: CredentialReadResult,
        expected: ByteArray,
    ) {
        result as CredentialReadResult.Present
        result.use {
            val actual = it.secret.copyBytes()
            try {
                assertThat(actual).isEqualTo(expected)
            } finally {
                actual.fill(0)
                expected.fill(0)
            }
        }
    }

    private class TestKeyProvider : CredentialKeyProvider {
        var key: SecretKey? =
            KeyGenerator.getInstance("AES").apply {
                init(256)
            }.generateKey()
        var failure: RuntimeException? = null
        var deleteCount = 0

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

        override fun delete() {
            deleteCount += 1
            key = null
            failure = null
        }
    }

    private class BlockingCorruptCipher(
        private val delegate: CredentialCipher,
    ) : CredentialCipher {
        val decryptStarted = CountDownLatch(1)
        val allowDecrypt = CountDownLatch(1)
        private val failNextDecrypt = AtomicBoolean(true)

        override fun encrypt(
            key: SecretKey,
            profileId: SourceProfileId,
            plaintext: ByteArray,
        ): EncryptedCredential = delegate.encrypt(key, profileId, plaintext)

        override fun decrypt(
            key: SecretKey,
            profileId: SourceProfileId,
            encrypted: EncryptedCredential,
        ): ByteArray {
            if (failNextDecrypt.compareAndSet(true, false)) {
                decryptStarted.countDown()
                check(allowDecrypt.await(5, TimeUnit.SECONDS))
                throw CredentialCipherCorruptedException()
            }
            return delegate.decrypt(key, profileId, encrypted)
        }
    }
}

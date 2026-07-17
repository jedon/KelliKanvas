package com.jedon.kellikanvas.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreKeyProviderConcurrencyTest {
    @Test
    fun providersSerializeAliasCreationProcessWide() {
        val access = FakeKeyStoreAccess()
        val providers = List(16) { AndroidKeystoreKeyProvider(access) }
        val executor = Executors.newFixedThreadPool(providers.size)
        try {
            val keys = providers.map { provider -> executor.submit<SecretKey> { provider.getOrCreate() } }
                .map { it.get(5, TimeUnit.SECONDS) }

            assertThat(access.generateCount.get()).isEqualTo(1)
            keys.forEach { assertThat(it).isSameInstanceAs(access.storedKey) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun getOrCreateRechecksAliasAfterGeneration() {
        val access = FakeKeyStoreAccess(returnDifferentGeneratedKey = true)

        val result = AndroidKeystoreKeyProvider(access).getOrCreate()

        assertThat(result).isSameInstanceAs(access.storedKey)
        assertThat(result).isNotSameInstanceAs(access.generatedKey)
    }

    private class FakeKeyStoreAccess(
        private val returnDifferentGeneratedKey: Boolean = false,
    ) : AndroidKeyStoreAccess {
        val generateCount = AtomicInteger()
        var storedKey: SecretKey? = null
        var generatedKey: SecretKey? = null

        override fun get(): SecretKey? = storedKey

        override fun generate(): SecretKey {
            generateCount.incrementAndGet()
            generatedKey = newKey()
            storedKey = if (returnDifferentGeneratedKey) newKey() else generatedKey
            return checkNotNull(generatedKey)
        }

        override fun delete() {
            storedKey = null
        }

        private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey()
    }
}

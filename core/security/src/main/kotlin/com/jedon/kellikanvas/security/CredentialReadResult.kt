package com.jedon.kellikanvas.security

import java.io.Closeable

sealed interface CredentialReadResult {
    data object Missing : CredentialReadResult

    data object RequiresReentry : CredentialReadResult

    class Present(
        val secret: CredentialSecret,
    ) : CredentialReadResult,
        Closeable {
        override fun close() = secret.close()

        override fun toString(): String = "CredentialReadResult.Present(<redacted>)"
    }
}

class CredentialVaultUnavailableException : RuntimeException("Credential vault temporarily unavailable")

class CredentialSecret(
    bytes: ByteArray,
) : Closeable {
    private var bytes: ByteArray? = bytes.copyOf()

    val isClosed: Boolean
        @Synchronized get() = bytes == null

    @Synchronized
    fun copyBytes(): ByteArray = checkNotNull(bytes) { "Credential secret is closed" }
        .copyOf()

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString(): String = "CredentialSecret(<redacted>)"
}

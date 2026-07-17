package com.jedon.kellikanvas.update

import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedUpdateSecurityTest {
    @Test
    fun `debug build without release pin cannot construct authenticator`() {
        assertThrows(IllegalStateException::class.java) {
            pinnedManifestAuthenticator()
        }
    }
}

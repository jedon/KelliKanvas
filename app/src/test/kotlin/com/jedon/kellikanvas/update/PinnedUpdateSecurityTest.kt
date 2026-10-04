package com.jedon.kellikanvas.update

import org.junit.Assert.assertNotNull
import org.junit.Test

class PinnedUpdateSecurityTest {
    @Test
    fun `distributed builds include a valid metadata verification key`() {
        assertNotNull(pinnedManifestAuthenticator())
    }
}

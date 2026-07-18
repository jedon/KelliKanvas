package com.jedon.kellikanvas.source.smb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HouseholdNasDefaultsTest {
    @Test
    fun primaryHostAndShareMatchProbe() {
        assertThat(HouseholdNasDefaults.PRIMARY_HOST).isEqualTo("192.168.68.81")
        assertThat(HouseholdNasDefaults.PORT).isEqualTo(445)
        assertThat(HouseholdNasDefaults.PRIMARY_SHARE.share).isEqualTo("Kelli")
        assertThat(HouseholdNasDefaults.HOST_CANDIDATES).contains("192.168.68.81")
        assertThat(HouseholdNasDefaults.HOST_CANDIDATES).contains("DarklingNAS")
    }

    @Test
    fun photoRootsAreNormalizedRelativePaths() {
        for (share in HouseholdNasDefaults.PHOTO_SHARES) {
            for (root in share.photoRoots) {
                assertThat(SmbPath.normalize(root)).isEqualTo(root.replace('\\', '/'))
                assertThat(root).doesNotContain("..")
                assertThat(root.startsWith("/")).isFalse()
            }
        }
        assertThat(HouseholdNasDefaults.PRIMARY_SHARE.photoRoots)
            .containsAtLeast("Digital Photos", "Cell Phone Photos", "Photos for frame TV and printing")
    }

    @Test
    fun defaultsContainNoCredentialFields() {
        val source =
            HouseholdNasDefaults::class.java.declaredFields
                .map { it.name.lowercase() }
        assertThat(source.none { it.contains("password") || it.contains("username") || it.contains("secret") })
            .isTrue()
    }
}

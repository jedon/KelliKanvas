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
    fun photoRootsAreOnlyFrameTv16x9() {
        assertThat(HouseholdNasDefaults.PHOTO_SHARES).hasSize(1)
        assertThat(HouseholdNasDefaults.PRIMARY_SHARE.photoRoots)
            .containsExactly(HouseholdNasDefaults.FRAME_TV_16X9_PATH)
        for (share in HouseholdNasDefaults.PHOTO_SHARES) {
            for (root in share.photoRoots) {
                assertThat(SmbPath.normalize(root)).isEqualTo(root.replace('\\', '/'))
                assertThat(root).doesNotContain("..")
                assertThat(root.startsWith("/")).isFalse()
            }
        }
        assertThat(HouseholdNasDefaults.PRIMARY_SHARE.photoRoots)
            .doesNotContain("Digital Photos")
        assertThat(HouseholdNasDefaults.PRIMARY_SHARE.photoRoots)
            .doesNotContain("Cell Phone Photos")
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

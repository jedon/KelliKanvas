package com.jedon.kellikanvas.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentifiersTest {
    @Test
    fun `source profile ID rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceProfileId(" \t")
        }
    }

    @Test
    fun `provider object ID rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderObjectId("")
        }
    }

    @Test
    fun `page cursor rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) {
            PageCursor("\n")
        }
    }

    @Test
    fun `asset key identity combines profile and provider object IDs`() {
        val profileA = SourceProfileId("profile-a")
        val profileB = SourceProfileId("profile-b")
        val objectId = ProviderObjectId("object-1")

        assertThat(AssetKey(profileA, objectId)).isEqualTo(AssetKey(profileA, objectId))
        assertThat(AssetKey(profileA, objectId)).isNotEqualTo(AssetKey(profileB, objectId))
    }

    @Test
    fun `identifier diagnostics do not expose provider values or URIs`() {
        val uri = "https://example.test/private/photo.jpg?token=secret"

        assertThat(ProviderObjectId(uri).toString()).doesNotContain(uri)
        assertThat(PageCursor("credential=secret").toString()).doesNotContain("secret")
        assertThat(
            AssetKey(
                SourceProfileId("profile-a"),
                ProviderObjectId(uri),
            ).toString(),
        ).doesNotContain(uri)
    }
}

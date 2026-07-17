package com.jedon.kellikanvas.source.saf

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafGrantAndProfileTest {
    @Test
    fun `grant flags retain read persistable and prefix only`() {
        val supplied =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        assertThat(SafTreeGrant.maskFlags(supplied))
            .isEqualTo(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        assertThat(SafTreeGrant.persistableModeFlags(supplied))
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `repair replaces grant without replacing profile ID`() {
        val profileId = SourceProfileId("stable-saf-profile")
        val original =
            SafProfile(
                id = profileId,
                grant = SafTreeGrant(
                    treeUri = Uri.parse("content://fixture/tree/old-root"),
                    documentId = "old-root",
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        val replacement =
            SafTreeGrant(
                treeUri = Uri.parse("content://fixture/tree/new-root"),
                documentId = "new-root",
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

        val repaired = original.repair(replacement)

        assertThat(repaired.id).isEqualTo(profileId)
        assertThat(repaired.grant).isEqualTo(replacement)
    }
}

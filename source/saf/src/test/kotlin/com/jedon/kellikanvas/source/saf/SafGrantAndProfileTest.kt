package com.jedon.kellikanvas.source.saf

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
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
    fun `persist rejects non-tree URI before takePersistable`() {
        val fixture = registerSafProvider()
        val nonTreeUri =
            DocumentsContract.buildDocumentUri(
                fixture.providerInfo.authority,
                TestDocumentsProvider.ROOT_ID,
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SafTreeGrant.persist(
                    resolver = fixture.resolver,
                    treeUri = nonTreeUri,
                    resultFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            }

        assertThat(error.message).contains("tree")
        assertThat(fixture.resolver.persistedUriPermissions).isEmpty()
    }

    @Test
    fun `repair replaces grant without replacing profile ID`() {
        val profileId = SourceProfileId("stable-saf-profile")
        val fixture = registerSafProvider()
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

        val repaired = original.repair(fixture.resolver, replacement)

        assertThat(repaired.id).isEqualTo(profileId)
        assertThat(repaired.grant).isEqualTo(replacement)
    }

    @Test
    fun `repair with different URI releases prior persistable grant`() {
        val oldFixture = registerSafProvider()
        val newFixture = registerSafProvider()
        oldFixture.resolver.takePersistableUriPermission(
            oldFixture.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        assertThat(
            oldFixture.resolver.persistedUriPermissions.any { it.uri == oldFixture.treeUri },
        ).isTrue()

        val profile =
            SafProfile(
                id = SourceProfileId("repair-release-profile"),
                grant = SafTreeGrant(
                    treeUri = oldFixture.treeUri,
                    documentId = TestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                ),
            )
        val replacement =
            SafTreeGrant(
                treeUri = newFixture.treeUri,
                documentId = TestDocumentsProvider.ROOT_ID,
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

        val repaired = profile.repair(oldFixture.resolver, replacement)

        assertThat(repaired.grant).isEqualTo(replacement)
        assertThat(
            oldFixture.resolver.persistedUriPermissions.any { it.uri == oldFixture.treeUri },
        ).isFalse()
    }
}

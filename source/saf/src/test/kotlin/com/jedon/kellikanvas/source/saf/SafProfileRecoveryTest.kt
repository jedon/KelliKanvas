package com.jedon.kellikanvas.source.saf

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafProfileRecoveryTest {
    @Test
    fun `replacement tree grant reuses profile ID and restores browsing after removal`() = runTest {
        val originalFixture = registerSafProvider()
        val profile = profile(originalFixture)
        val unavailableAdapter =
            SafSourceAdapter(
                profile,
                ContentResolverSafDocuments(originalFixture.resolver),
            )
        originalFixture.provider.mode = TestDocumentsProvider.Mode.REMOVED
        expectFailure<SourceFailure.SourceUnavailable> {
            unavailableAdapter.listChildren(unavailableAdapter.root, null)
        }

        val replacementFixture = registerSafProvider()
        val repaired =
            profile.repair(
                originalFixture.resolver,
                SafTreeGrant(
                    treeUri = replacementFixture.treeUri,
                    documentId = TestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        val recoveredAdapter =
            SafSourceAdapter(
                repaired,
                ContentResolverSafDocuments(replacementFixture.resolver),
            )

        assertThat(repaired.id).isEqualTo(profile.id)
        assertThat(recoveredAdapter.listChildren(recoveredAdapter.root, null).items).hasSize(3)
    }

    @Test
    fun `replacement grant reuses profile ID and restores browsing after revocation`() = runTest {
        val fixture = registerSafProvider()
        val profile = profile(fixture)
        val revokedAdapter = SafSourceAdapter(profile, ContentResolverSafDocuments(fixture.resolver))
        fixture.provider.mode = TestDocumentsProvider.Mode.REVOKED
        expectFailure<SourceFailure.PermissionRevoked> {
            revokedAdapter.listChildren(revokedAdapter.root, null)
        }

        fixture.provider.mode = TestDocumentsProvider.Mode.ACTIVE
        val repaired = profile.repair(fixture.resolver, profile.grant)
        val recoveredAdapter = SafSourceAdapter(repaired, ContentResolverSafDocuments(fixture.resolver))

        assertThat(repaired.id).isEqualTo(profile.id)
        assertThat(recoveredAdapter.listChildren(recoveredAdapter.root, null).items).hasSize(3)
    }

    private fun profile(fixture: RobolectricSafProvider): SafProfile = SafProfile(
        id = SourceProfileId("existing-saf-profile"),
        grant = SafTreeGrant(
            treeUri = fixture.treeUri,
            documentId = TestDocumentsProvider.ROOT_ID,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ),
    )

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}

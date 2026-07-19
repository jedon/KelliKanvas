package com.jedon.kellikanvas.source

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class UnsupportedSourceAdapterTest {
    private val profileId = SourceProfileId("unsupported-profile")
    private val folder = FolderRef(profileId, ProviderObjectId("folder"))
    private val asset =
        AssetRef(
            profileId = profileId,
            objectId = ProviderObjectId("asset"),
            mimeType = "image/jpeg",
        )

    @Test
    fun `reports kind and fails closed on every source operation`() = runTest {
        val adapter =
            UnsupportedSourceAdapter(
                kind = SourceKind.HTTP,
                profileId = profileId,
                reason = "HTTP source is not implemented",
            )

        assertThat(adapter.profileId).isEqualTo(profileId)
        assertThat(adapter.kind).isEqualTo(SourceKind.HTTP)

        assertUnsupported { adapter.probe() }
        assertUnsupported { adapter.listChildren(folder, null) }
        assertUnsupported { adapter.metadata(asset) }
        assertUnsupported { adapter.open(asset) }
    }

    @Test
    fun `does not report authentication required or success`() = runTest {
        val adapter =
            UnsupportedSourceAdapter(
                kind = SourceKind.SMB,
                profileId = profileId,
                reason = "SMB source is not implemented",
            )

        val failure = assertUnsupported { adapter.probe() }
        assertThat(failure).isNotInstanceOf(SourceFailure.AuthenticationRequired::class.java)
        assertThat(failure.safeDetail).contains("not implemented")
    }

    private suspend fun assertUnsupported(block: suspend () -> Any?): SourceFailure {
        try {
            block()
            fail("Expected SourceFailure for unsupported source")
            error("unreachable")
        } catch (failure: SourceFailure) {
            assertThat(failure.profileId).isEqualTo(profileId)
            assertThat(failure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
            return failure
        }
    }
}

package com.jedon.kellikanvas.source.smb

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

class SmbSourceAdapterTest {
    private val profileId = SourceProfileId("smb-profile")
    private val folder = FolderRef(profileId, ProviderObjectId("folder"))
    private val asset =
        AssetRef(
            profileId = profileId,
            objectId = ProviderObjectId("asset"),
            mimeType = "image/jpeg",
        )

    @Test
    fun `reports SMB kind and fails closed on every operation`() = runTest {
        val adapter = SmbSourceAdapter(profileId)

        assertThat(adapter.kind).isEqualTo(SourceKind.SMB)
        assertThat(adapter.profileId).isEqualTo(profileId)

        assertProtocolFailure { adapter.probe() }
        assertProtocolFailure { adapter.listChildren(folder, null) }
        assertProtocolFailure { adapter.metadata(asset) }
        assertProtocolFailure { adapter.open(asset) }
    }

    private suspend fun assertProtocolFailure(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected ProtocolFailure for unsupported SMB source")
        } catch (failure: SourceFailure.ProtocolFailure) {
            assertThat(failure.profileId).isEqualTo(profileId)
            assertThat(failure.safeDetail).contains("not implemented")
        } catch (failure: SourceFailure.AuthenticationRequired) {
            fail("Must not report AuthenticationRequired: $failure")
        }
    }
}

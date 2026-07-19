package com.jedon.kellikanvas.source.saf

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafSourceAdapterCursorFailureTest {
    @Test
    fun `invalid page cursor maps to ProtocolFailure`() = runTest {
        val adapter = adapter()

        val failure =
            expectFailure {
                adapter.listChildren(adapter.root, PageCursor("not-a-valid-saf-cursor"))
            }

        assertThat(failure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
    }

    @Test
    fun `out of range page cursor maps to ProtocolFailure`() = runTest {
        val adapter = adapter()

        val failure =
            expectFailure {
                adapter.listChildren(adapter.root, encodeCursor(offset = 10_000))
            }

        assertThat(failure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
    }

    private fun adapter(): SafSourceAdapter {
        val fixture = registerSafProvider()
        val profile =
            SafProfile(
                id = SourceProfileId("saf-cursor-profile"),
                grant = SafTreeGrant(
                    treeUri = fixture.treeUri,
                    documentId = TestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        return SafSourceAdapter(profile, ContentResolverSafDocuments(fixture.resolver))
    }

    private fun encodeCursor(offset: Int): PageCursor {
        val plain = "saf-page-v1:$offset".toByteArray(StandardCharsets.UTF_8)
        return PageCursor(Base64.getUrlEncoder().withoutPadding().encodeToString(plain))
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected failure")
    }
}

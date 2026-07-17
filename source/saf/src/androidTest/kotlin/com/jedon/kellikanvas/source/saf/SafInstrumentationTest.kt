package com.jedon.kellikanvas.source.saf

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SafInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val authority = "${context.packageName}.saf.documents"
    private val treeUri =
        DocumentsContract.buildTreeDocumentUri(authority, AndroidTestDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.ACTIVE
    }

    @After
    fun tearDown() {
        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.ACTIVE
    }

    @Test
    fun nestedBrowsingUsesRegisteredDocumentsProvider() = runTest {
        val adapter = adapter()

        val rootEntries = adapter.listChildren(adapter.root, null).items
        val landscape =
            rootEntries
                .filterIsInstance<SourceEntry.Folder>()
                .single { it.ref.objectId.value == AndroidTestDocumentsProvider.LANDSCAPE_ID }
        val nested = adapter.listChildren(landscape.ref, null).items

        assertThat(rootEntries.map(SourceEntry::name)).containsExactly("Landscape", "Portrait").inOrder()
        assertThat(nested.single().name).isEqualTo("Mountain")
    }

    @Test
    fun revokedAndRemovedProviderStatesMapToSourceFailures() = runTest {
        val adapter = adapter()
        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.REVOKED
        expectFailure<SourceFailure.PermissionRevoked> {
            adapter.listChildren(adapter.root, null)
        }

        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.REMOVED
        expectFailure<SourceFailure.SourceUnavailable> {
            adapter.listChildren(adapter.root, null)
        }
    }

    @Test
    fun streamOwnsAndClosesProviderDescriptor() = runTest {
        val observer = CloseObserver()
        val adapter = adapter(observer)
        val landscape =
            adapter
                .listChildren(adapter.root, null)
                .items
                .filterIsInstance<SourceEntry.Folder>()
                .single { it.ref.objectId.value == AndroidTestDocumentsProvider.LANDSCAPE_ID }
        val photo =
            adapter
                .listChildren(landscape.ref, null)
                .items
                .filterIsInstance<SourceEntry.Photo>()
                .single()

        val stream = adapter.open(photo.asset)
        stream.read(Buffer(), 1)
        stream.close()

        assertThat(observer.closed.get()).isEqualTo(1)
    }

    @Test
    fun registeredProviderAllowsReadAndRejectsWrite() {
        val resolver = context.contentResolver
        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                AndroidTestDocumentsProvider.PHOTO_ID,
            )

        resolver.openFileDescriptor(documentUri, "r").use {
            assertThat(it).isNotNull()
        }
        expectSyncFailure<SecurityException> {
            resolver.openFileDescriptor(documentUri, "w")
        }

        val masked =
            SafTreeGrant.maskFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        assertThat(masked and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    private fun adapter(observer: SafReadObserver = CloseObserver()): SafSourceAdapter {
        val profile =
            SafProfile(
                id = SourceProfileId("instrumentation-profile"),
                grant = SafTreeGrant(
                    treeUri = treeUri,
                    documentId = AndroidTestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        return SafSourceAdapter(
            profile,
            ContentResolverSafDocuments(context.contentResolver, readObserver = observer),
        )
    }

    private inline fun <reified T : Throwable> expectSyncFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class CloseObserver : SafReadObserver {
        val closed = AtomicInteger()

        override fun onClose(documentId: String) {
            closed.incrementAndGet()
        }
    }
}

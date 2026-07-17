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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val providerContext = instrumentation.context
    private val targetContext = instrumentation.targetContext
    private val targetPackageName = targetContext.packageName
    private val authority = "${providerContext.packageName}.saf.documents"
    private val treeUri =
        DocumentsContract.buildTreeDocumentUri(authority, AndroidTestDocumentsProvider.ROOT_ID)
    private val rootDocumentUri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, AndroidTestDocumentsProvider.ROOT_ID)
    private val grantFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    @Before
    fun setUp() {
        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.ACTIVE
        providerContext.grantUriPermission(targetPackageName, treeUri, grantFlags)
        providerContext.grantUriPermission(targetPackageName, rootDocumentUri, grantFlags)
    }

    @After
    fun tearDown() {
        providerContext.revokeUriPermission(
            targetPackageName,
            rootDocumentUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        providerContext.revokeUriPermission(
            targetPackageName,
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
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
    fun revokedAndRemovedStatesRecoverWithSameProfile() = runTest {
        val adapter = adapter()
        val profileId = adapter.profileId
        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.REVOKED
        expectFailure<SourceFailure.PermissionRevoked> {
            adapter.listChildren(adapter.root, null)
        }

        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.REMOVED
        expectFailure<SourceFailure.SourceUnavailable> {
            adapter.listChildren(adapter.root, null)
        }

        AndroidTestDocumentsProvider.mode = AndroidTestDocumentsProvider.Mode.ACTIVE
        val recovered = adapter.listChildren(adapter.root, null)

        assertThat(adapter.profileId).isEqualTo(profileId)
        assertThat(recovered.items.map(SourceEntry::name)).containsExactly("Landscape", "Portrait").inOrder()
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
    fun targetResolverUsesExplicitTreeGrantAndProviderRejectsWrite() {
        val resolver = targetContext.contentResolver
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                AndroidTestDocumentsProvider.ROOT_ID,
            )
        val documentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                AndroidTestDocumentsProvider.PHOTO_ID,
            )

        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        ).use { cursor ->
            assertThat(cursor).isNotNull()
            assertThat(cursor?.moveToFirst()).isTrue()
        }
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
            ContentResolverSafDocuments(targetContext.contentResolver, readObserver = observer),
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

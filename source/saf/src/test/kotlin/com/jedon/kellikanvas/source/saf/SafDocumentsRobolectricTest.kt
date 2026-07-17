package com.jedon.kellikanvas.source.saf

import android.Manifest
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafDocumentsRobolectricTest {
    @Test
    fun `provider attaches with secure metadata and serves resolver query`() {
        val fixture = registerSafProvider()
        val info = fixture.providerInfo
        val rootDocumentUri =
            DocumentsContract.buildDocumentUriUsingTree(
                fixture.treeUri,
                TestDocumentsProvider.ROOT_ID,
            )

        assertThat(info.authority).isEqualTo(fixture.treeUri.authority)
        assertThat(info.exported).isTrue()
        assertThat(info.grantUriPermissions).isTrue()
        assertThat(info.readPermission).isEqualTo(Manifest.permission.MANAGE_DOCUMENTS)
        assertThat(info.writePermission).isEqualTo(Manifest.permission.MANAGE_DOCUMENTS)
        assertThat(fixture.provider.context).isNotNull()
        fixture.resolver.query(rootDocumentUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            .use { cursor ->
                assertThat(cursor).isNotNull()
                assertThat(cursor?.moveToFirst()).isTrue()
                assertThat(cursor?.getString(0)).isEqualTo(TestDocumentsProvider.ROOT_ID)
            }
    }

    @Test
    fun `resolver reads canonical IDs and bounded document columns`() = runTest {
        val fixture = registerSafProvider()
        val documents = ContentResolverSafDocuments(fixture.resolver)
        val root = documents.document(fixture.treeUri, TestDocumentsProvider.ROOT_ID)
        val children =
            documents.children(
                fixture.treeUri,
                TestDocumentsProvider.ROOT_ID,
            )

        assertThat(root?.documentId).isEqualTo(TestDocumentsProvider.ROOT_ID)
        assertThat(children.map(SafDocument::documentId))
            .containsAtLeast(
                TestDocumentsProvider.LANDSCAPE_ID,
                TestDocumentsProvider.PORTRAIT_ID,
                TestDocumentsProvider.COVER_ID,
            )
        assertThat(fixture.provider.lastProjection.get())
            .containsExactly(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            ).inOrder()
    }

    @Test
    fun `resolver opens provider file descriptor read only`() = runTest {
        val fixture = registerSafProvider()
        val documents = ContentResolverSafDocuments(fixture.resolver)
        val opened =
            documents.openRead(
                fixture.treeUri,
                TestDocumentsProvider.COVER_ID,
            )

        val bytes = ParcelFileDescriptorReader.readAll(opened)

        assertThat(bytes).isEqualTo(TestDocumentsProvider.COVER_BYTES)
    }
}

private object ParcelFileDescriptorReader {
    fun readAll(opened: SafOpenDocument): ByteArray = android.os.ParcelFileDescriptor
        .AutoCloseInputStream(opened.descriptor)
        .use { it.readBytes() }
}

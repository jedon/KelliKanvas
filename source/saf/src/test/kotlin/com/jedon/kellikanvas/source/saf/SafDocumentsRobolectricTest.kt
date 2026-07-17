package com.jedon.kellikanvas.source.saf

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafDocumentsRobolectricTest {
    private lateinit var documents: ContentResolverSafDocuments

    @Before
    fun setUp() {
        val provider = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(TestDocumentsProvider.AUTHORITY, provider)
        documents = ContentResolverSafDocuments(RuntimeEnvironment.getApplication().contentResolver)
    }

    @Test
    fun `resolver reads canonical IDs and bounded document columns`() = runTest {
        val root = documents.document(TestDocumentsProvider.TREE_URI, TestDocumentsProvider.ROOT_ID)
        val children =
            documents.children(
                TestDocumentsProvider.TREE_URI,
                TestDocumentsProvider.ROOT_ID,
            )

        assertThat(root?.documentId).isEqualTo(TestDocumentsProvider.ROOT_ID)
        assertThat(children.map(SafDocument::documentId))
            .containsAtLeast(
                TestDocumentsProvider.LANDSCAPE_ID,
                TestDocumentsProvider.PORTRAIT_ID,
                TestDocumentsProvider.COVER_ID,
            )
    }

    @Test
    fun `resolver opens provider file descriptor read only`() = runTest {
        val opened =
            documents.openRead(
                TestDocumentsProvider.TREE_URI,
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

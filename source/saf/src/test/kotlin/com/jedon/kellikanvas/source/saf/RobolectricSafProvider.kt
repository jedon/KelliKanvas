package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

data class RobolectricSafProvider(
    val provider: TestDocumentsProvider,
    val resolver: ContentResolver,
    val treeUri: Uri,
)

fun registerSafProvider(): RobolectricSafProvider {
    val authority = "com.jedon.kellikanvas.source.saf.test.documents.${NEXT_AUTHORITY.incrementAndGet()}"
    val provider =
        Robolectric
            .buildContentProvider(TestDocumentsProvider::class.java)
            .create(authority)
            .get()
    return RobolectricSafProvider(
        provider = provider,
        resolver = RuntimeEnvironment.getApplication().contentResolver,
        treeUri = DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.ROOT_ID),
    )
}

private val NEXT_AUTHORITY = AtomicInteger()

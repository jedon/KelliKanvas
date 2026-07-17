package com.jedon.kellikanvas.source.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

class BlockingQueryProvider : ContentProvider() {
    lateinit var block: TestDocumentsProvider.BlockingCall
    val closedCursorCount = AtomicInteger()
    var cancelImmediately: Boolean = false

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor = query(uri, projection, null, null, null, cancellationSignal)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = error("CancellationSignal query overload required")

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        if (cancelImmediately) throw OperationCanceledException()
        block.await(cancellationSignal)
        val columns = requireNotNull(projection)
        return TrackingCursor(columns, closedCursorCount).apply {
            addRow(
                columns.map {
                    when (it) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> TestDocumentsProvider.COVER_ID
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "Welcome"
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> "image/jpeg"
                        DocumentsContract.Document.COLUMN_SIZE -> TestDocumentsProvider.COVER_BYTES.size.toLong()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException()

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    private class TrackingCursor(
        columns: Array<out String>,
        private val closedCount: AtomicInteger,
    ) : MatrixCursor(columns) {
        override fun close() {
            if (!isClosed) closedCount.incrementAndGet()
            super.close()
        }
    }
}

data class BlockingQueryFixture(
    val provider: BlockingQueryProvider,
    val treeUri: Uri,
)

fun registerBlockingQueryProvider(
    behavior: TestDocumentsProvider.CancellationBehavior,
): BlockingQueryFixture {
    val authority = "com.jedon.kellikanvas.source.saf.test.blocking.${NEXT_BLOCKING_AUTHORITY.incrementAndGet()}"
    val provider =
        Robolectric
            .buildContentProvider(BlockingQueryProvider::class.java)
            .create(authority)
            .get()
    provider.block = TestDocumentsProvider.BlockingCall(behavior)
    return BlockingQueryFixture(
        provider = provider,
        treeUri = DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.ROOT_ID),
    )
}

fun BlockingQueryFixture.documents(
    executor: java.util.concurrent.Executor,
): ContentResolverSafDocuments = ContentResolverSafDocuments(
    RuntimeEnvironment.getApplication().contentResolver,
    executor,
)

private val NEXT_BLOCKING_AUTHORITY = AtomicInteger()

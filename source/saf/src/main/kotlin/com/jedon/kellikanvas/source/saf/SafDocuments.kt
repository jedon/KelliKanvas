package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

const val MAX_SAF_FOLDER_ENTRIES: Int = 10_000

data class SafDocument(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val modifiedAtMillis: Long?,
    val flags: Int,
) {
    val isFolder: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    val isPhoto: Boolean
        get() = mimeType.startsWith("image/")

    init {
        require(documentId.isNotBlank()) { "SAF document ID must not be blank" }
        require(displayName.isNotBlank()) { "SAF display name must not be blank" }
        require(mimeType.isNotBlank()) { "SAF MIME type must not be blank" }
        require(size == null || size >= 0) { "SAF size must be nonnegative" }
        require(modifiedAtMillis == null || modifiedAtMillis >= 0) {
            "SAF modified time must be nonnegative"
        }
    }
}

interface SafDocuments {
    suspend fun document(
        treeUri: Uri,
        documentId: String,
    ): SafDocument?

    suspend fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maxEntries: Int = MAX_SAF_FOLDER_ENTRIES,
    ): List<SafDocument>

    suspend fun openRead(
        treeUri: Uri,
        documentId: String,
    ): SafOpenDocument
}

interface SafReadObserver {
    fun onOpen(documentId: String) = Unit

    suspend fun beforeRead(documentId: String) = Unit

    fun onBytesRead(
        documentId: String,
        byteCount: Long,
    ) = Unit

    fun onClose(documentId: String) = Unit
}

interface SafQueryObserver {
    suspend fun beforeParse() = Unit
}

class SafOpenDocument internal constructor(
    val descriptor: ParcelFileDescriptor,
    private val documentId: String,
    private val observer: SafReadObserver,
) : Closeable {
    private val closed = AtomicBoolean()

    internal suspend fun beforeRead() = observer.beforeRead(documentId)

    internal fun onBytesRead(byteCount: Long) = observer.onBytesRead(documentId, byteCount)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            descriptor.close()
        } finally {
            observer.onClose(documentId)
        }
    }
}

class ContentResolverSafDocuments(
    private val resolver: ContentResolver,
    private val ioExecutor: Executor = Dispatchers.IO.asExecutor(),
    private val readObserver: SafReadObserver = NoOpSafReadObserver,
    private val queryObserver: SafQueryObserver = NoOpSafQueryObserver,
) : SafDocuments {
    override suspend fun document(
        treeUri: Uri,
        documentId: String,
    ): SafDocument? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        query(uri).use { cursor ->
            queryObserver.beforeParse()
            coroutineContext.ensureActive()
            if (cursor == null || !cursor.moveToFirst()) null else cursor.readDocument()
        }
    }

    override suspend fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maxEntries: Int,
    ): List<SafDocument> {
        require(maxEntries in 1..MAX_SAF_FOLDER_ENTRIES) {
            "SAF folder entry bound must be between 1 and $MAX_SAF_FOLDER_ENTRIES"
        }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        query(uri).use { cursor ->
            if (cursor == null) throw IOException("SAF provider returned no cursor")
            queryObserver.beforeParse()
            buildList {
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    if (size == maxEntries) throw SafFolderTooLargeException()
                    add(cursor.readDocument())
                }
            }
        }
    }

    override suspend fun openRead(
        treeUri: Uri,
        documentId: String,
    ): SafOpenDocument {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val descriptor =
            awaitCloseable { signal ->
                resolver.openFileDescriptor(uri, "r", signal)
            } ?: throw FileNotFoundException("SAF provider returned no file descriptor")
        val opened = SafOpenDocument(descriptor, documentId, readObserver)
        return try {
            coroutineContext.ensureActive()
            readObserver.onOpen(documentId)
            opened
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    private suspend fun query(uri: Uri): Cursor? = awaitCloseable { signal ->
        resolver.query(uri, PROJECTION, null, null, null, signal)
    }

    private suspend fun <T : Closeable> awaitCloseable(
        operation: (CancellationSignal) -> T?,
    ): T? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val completed = AtomicBoolean()
        continuation.invokeOnCancellation {
            runCatching { signal.cancel() }
            completed.compareAndSet(false, true)
        }

        try {
            ioExecutor.execute {
                try {
                    val resource = operation(signal)
                    if (completed.compareAndSet(false, true)) {
                        continuation.resume(resource) { _, lateResource, _ ->
                            runCatching { lateResource?.close() }
                        }
                    } else {
                        resource?.close()
                    }
                } catch (failure: OperationCanceledException) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.cancel(failure.asCoroutineCancellation())
                    }
                } catch (failure: Throwable) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        } catch (failure: Throwable) {
            if (completed.compareAndSet(false, true)) {
                continuation.resumeWithException(failure)
            }
        }
    }

    private fun Cursor.readDocument(): SafDocument = SafDocument(
        documentId = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
        displayName = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
        mimeType = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)),
        size = nullableLong(DocumentsContract.Document.COLUMN_SIZE),
        modifiedAtMillis = nullableLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        flags = getInt(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)),
    )

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index).takeIf { it >= 0 }
    }

    private companion object {
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
    }
}

class SafFolderTooLargeException : IOException("SAF folder exceeds the supported entry bound")

private object NoOpSafReadObserver : SafReadObserver
private object NoOpSafQueryObserver : SafQueryObserver

private fun OperationCanceledException.asCoroutineCancellation(): CancellationException = CancellationException(
    "SAF platform operation cancelled",
).also { it.initCause(this) }

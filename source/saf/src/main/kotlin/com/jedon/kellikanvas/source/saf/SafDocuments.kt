package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext

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

class SafOpenDocument(
    val descriptor: ParcelFileDescriptor,
    val beforeRead: suspend () -> Unit = {},
    val onBytesRead: (Long) -> Unit = {},
    val onClose: () -> Unit = {},
)

class ContentResolverSafDocuments(
    private val resolver: ContentResolver,
) : SafDocuments {
    override suspend fun document(
        treeUri: Uri,
        documentId: String,
    ): SafDocument? = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        query(uri).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) null else cursor.readDocument()
        }
    }

    override suspend fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maxEntries: Int,
    ): List<SafDocument> = withContext(Dispatchers.IO) {
        require(maxEntries in 1..MAX_SAF_FOLDER_ENTRIES) {
            "SAF folder entry bound must be between 1 and $MAX_SAF_FOLDER_ENTRIES"
        }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        query(uri).use { cursor ->
            if (cursor == null) throw IOException("SAF provider returned no cursor")
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
    ): SafOpenDocument = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        SafOpenDocument(
            resolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("SAF provider returned no file descriptor"),
        )
    }

    private suspend fun query(uri: Uri): Cursor? {
        val signal = CancellationSignal()
        val cancellationHandle =
            coroutineContext[Job]?.invokeOnCompletion { failure ->
                if (failure is CancellationException) signal.cancel()
            }
        return try {
            resolver.query(uri, PROJECTION, null, null, null, signal)
        } finally {
            cancellationHandle?.dispose()
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

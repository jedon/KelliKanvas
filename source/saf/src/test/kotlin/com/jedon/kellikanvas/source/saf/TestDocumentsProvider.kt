package com.jedon.kellikanvas.source.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileNotFoundException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TestDocumentsProvider : DocumentsProvider() {
    enum class Mode {
        ACTIVE,
        REVOKED,
        REMOVED,
    }

    enum class CancellationBehavior {
        THROW,
        RETURN_RESOURCE,
    }

    class BlockingCall(
        private val behavior: CancellationBehavior,
    ) {
        val started = CompletableDeferred<Unit>()
        val signalCancelled = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)

        fun await(signal: CancellationSignal?) {
            started.complete(Unit)
            signal?.setOnCancelListener {
                signalCancelled.complete(Unit)
                if (behavior == CancellationBehavior.THROW) release.countDown()
            }
            try {
                release.await()
                if (signal?.isCanceled == true && behavior == CancellationBehavior.THROW) {
                    throw OperationCanceledException()
                }
            } finally {
                finished.complete(Unit)
            }
        }

        fun release() = release.countDown()
    }

    var mode: Mode = Mode.ACTIVE
    val ioCount = AtomicInteger()
    val closedCursorCount = AtomicInteger()
    val lastOpenedDescriptor = AtomicReference<ParcelFileDescriptor?>()
    val lastProjection = AtomicReference<List<String>>(emptyList())
    val onCursorMove = AtomicReference<(() -> Unit)?>(null)
    private val missing = Collections.synchronizedSet(mutableSetOf<String>())
    private val nextDescriptorBlock = AtomicReference<BlockingCall?>()

    override fun onCreate(): Boolean = true

    fun remove(documentId: String) {
        missing += documentId
    }

    fun blockNextDescriptorOpen(
        behavior: CancellationBehavior = CancellationBehavior.THROW,
    ): BlockingCall = BlockingCall(behavior).also { nextDescriptorBlock.set(it) }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_TITLE -> "Fixture"
                        DocumentsContract.Root.COLUMN_FLAGS -> 0
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor {
        beforeIo()
        val columns = projection ?: DOCUMENT_PROJECTION
        lastProjection.set(columns.toList())
        return TrackingCursor(columns, closedCursorCount, onCursorMove).apply {
            node(documentId)?.takeUnless { documentId in missing }?.let {
                addDocumentRow(columns, it.document)
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = queryChildren(parentDocumentId, projection)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        beforeIo()
        if (mode != "r") throw SecurityException("Fixture is read-only")
        nextDescriptorBlock.getAndSet(null)?.await(signal)
        val bytes =
            node(documentId)?.takeUnless { documentId in missing }?.bytes
                ?: throw FileNotFoundException("Missing fixture document")
        return ParcelFileDescriptor
            .open(createPayloadFile(bytes), ParcelFileDescriptor.MODE_READ_ONLY)
            .also(lastOpenedDescriptor::set)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        var current = node(documentId)?.parentId
        while (current != null) {
            if (current == parentDocumentId) return true
            current = node(current)?.parentId
        }
        return false
    }

    override fun toString(): String = "TestDocumentsProvider(mode=$mode)"

    private fun queryChildren(
        parentDocumentId: String,
        projection: Array<out String>?,
    ): Cursor {
        beforeIo()
        val columns = projection ?: DOCUMENT_PROJECTION
        lastProjection.set(columns.toList())
        return TrackingCursor(columns, closedCursorCount, onCursorMove).apply {
            NODES.values
                .filter { it.parentId == parentDocumentId && it.document.documentId !in missing }
                .forEach { addDocumentRow(columns, it.document) }
        }
    }

    private fun beforeIo() {
        ioCount.incrementAndGet()
        when (mode) {
            Mode.ACTIVE -> Unit
            Mode.REVOKED -> throw SecurityException("Fixture grant revoked")
            Mode.REMOVED -> throw FileNotFoundException("Fixture media removed")
        }
    }

    private fun node(documentId: String): Node? = NODES[documentId]

    private fun MatrixCursor.addDocumentRow(
        columns: Array<out String>,
        document: SafDocument,
    ) {
        addRow(
            columns.map { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> document.documentId
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document.displayName
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> document.mimeType
                    DocumentsContract.Document.COLUMN_SIZE -> document.size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> document.modifiedAtMillis
                    DocumentsContract.Document.COLUMN_FLAGS -> document.flags
                    else -> null
                }
            }.toTypedArray(),
        )
    }

    private fun createPayloadFile(bytes: ByteArray): File = File
        .createTempFile("saf-fixture-", ".bin")
        .apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    data class Node(
        val parentId: String?,
        val document: SafDocument,
        val bytes: ByteArray? = null,
    )

    private class TrackingCursor(
        columns: Array<out String>,
        private val closedCount: AtomicInteger,
        private val onMoveCallback: AtomicReference<(() -> Unit)?>,
    ) : MatrixCursor(columns) {
        override fun onMove(
            oldPosition: Int,
            newPosition: Int,
        ): Boolean {
            onMoveCallback.getAndSet(null)?.invoke()
            return super.onMove(oldPosition, newPosition)
        }

        override fun close() {
            if (!isClosed) closedCount.incrementAndGet()
            super.close()
        }
    }

    companion object {
        const val ROOT_ID = "root"
        const val LANDSCAPE_ID = "folder-landscape"
        const val PORTRAIT_ID = "folder-portrait"
        const val COVER_ID = "photo-cover"
        const val MOUNTAIN_ID = "photo-mountain"
        const val PERSON_ID = "photo-person"

        val COVER_BYTES: ByteArray = "cover-payload".encodeToByteArray()
        val MOUNTAIN_BYTES: ByteArray = "mountain-payload".encodeToByteArray()
        val PERSON_BYTES: ByteArray = "person-payload".encodeToByteArray()

        val NODES: Map<String, Node> =
            listOf(
                Node(
                    parentId = null,
                    document = document(ROOT_ID, "Fixture root", DocumentsContract.Document.MIME_TYPE_DIR),
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document(COVER_ID, "Welcome", "image/jpeg", COVER_BYTES.size.toLong()),
                    bytes = COVER_BYTES,
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document(PORTRAIT_ID, "Portrait", DocumentsContract.Document.MIME_TYPE_DIR),
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document(LANDSCAPE_ID, "Landscape", DocumentsContract.Document.MIME_TYPE_DIR),
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document("ignored-notes", "Notes", "text/plain", 5),
                    bytes = "notes".encodeToByteArray(),
                ),
                Node(
                    parentId = LANDSCAPE_ID,
                    document = document(MOUNTAIN_ID, "Mountain", "image/jpeg", MOUNTAIN_BYTES.size.toLong()),
                    bytes = MOUNTAIN_BYTES,
                ),
                Node(
                    parentId = PORTRAIT_ID,
                    document = document(PERSON_ID, "Person", "image/png", PERSON_BYTES.size.toLong()),
                    bytes = PERSON_BYTES,
                ),
            ).associateBy { it.document.documentId }

        private val ROOT_PROJECTION =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
            )

        private val DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )

        private fun document(
            id: String,
            name: String,
            mimeType: String,
            size: Long? = null,
        ) = SafDocument(
            documentId = id,
            displayName = name,
            mimeType = mimeType,
            size = size,
            modifiedAtMillis = 1_700_000_000_000,
            flags = 0,
        )
    }
}

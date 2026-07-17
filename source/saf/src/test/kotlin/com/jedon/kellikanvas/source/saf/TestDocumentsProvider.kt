package com.jedon.kellikanvas.source.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class TestDocumentsProvider :
    DocumentsProvider(),
    SafDocuments {
    enum class Mode {
        ACTIVE,
        REVOKED,
        REMOVED,
    }

    data class ReadObservation(
        var opened: Int = 0,
        var bytesRead: Long = 0,
        var closed: Int = 0,
    )

    class Stall {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
    }

    var mode: Mode = Mode.ACTIVE
    var ioCount: Int = 0
        private set
    private val missing = mutableSetOf<String>()
    private val observations = mutableMapOf<String, ReadObservation>()
    private var listingStall: Stall? = null
    private val readStalls = mutableMapOf<String, Stall>()

    override fun onCreate(): Boolean = true

    fun remove(documentId: String) {
        missing += documentId
    }

    fun stallNextListing(): Stall = Stall().also { listingStall = it }

    fun stallNextRead(documentId: String): Stall = Stall().also { readStalls[documentId] = it }

    fun observation(documentId: String): ReadObservation = observations.getOrPut(documentId) { ReadObservation() }.copy()

    override suspend fun document(
        treeUri: Uri,
        documentId: String,
    ): SafDocument? {
        beforeIo()
        return node(documentId)?.takeUnless { documentId in missing }?.document
    }

    override suspend fun children(
        treeUri: Uri,
        parentDocumentId: String,
        maxEntries: Int,
    ): List<SafDocument> {
        beforeIo()
        listingStall?.also { stall ->
            listingStall = null
            stall.started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stall.closed.complete(Unit)
            }
        }
        return NODES.values
            .filter { it.parentId == parentDocumentId && it.document.documentId !in missing }
            .take(maxEntries + 1)
            .map(Node::document)
    }

    override suspend fun openRead(
        treeUri: Uri,
        documentId: String,
    ): SafOpenDocument {
        beforeIo()
        val node = node(documentId)?.takeUnless { documentId in missing }
            ?: throw FileNotFoundException("Missing fixture document")
        val bytes = node.bytes ?: throw FileNotFoundException("Fixture document is not a file")
        val observation = observations.getOrPut(documentId) { ReadObservation() }
        observation.opened += 1
        val file = createPayloadFile(bytes)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val stall = readStalls.remove(documentId)
        return SafOpenDocument(
            descriptor = descriptor,
            beforeRead = {
                stall?.let {
                    it.started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        it.closed.complete(Unit)
                    }
                }
            },
            onBytesRead = { observation.bytesRead += it },
            onClose = {
                observation.closed += 1
                file.delete()
            },
        )
    }

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
        checkProviderMode()
        val columns = projection ?: DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            node(documentId)?.takeUnless { documentId in missing }?.let { addDocumentRow(columns, it.document) }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        checkProviderMode()
        val columns = projection ?: DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            NODES.values
                .filter { it.parentId == parentDocumentId && it.document.documentId !in missing }
                .forEach { addDocumentRow(columns, it.document) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        checkProviderMode()
        if (mode != "r") throw SecurityException("Fixture is read-only")
        val bytes =
            node(documentId)?.takeUnless { documentId in missing }?.bytes
                ?: throw FileNotFoundException("Missing fixture document")
        return ParcelFileDescriptor.open(createPayloadFile(bytes), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean = node(documentId)?.parentId == parentDocumentId

    override fun toString(): String = "TestDocumentsProvider(mode=$mode)"

    private fun beforeIo() {
        ioCount += 1
        checkProviderMode()
    }

    private fun checkProviderMode() {
        when (mode) {
            Mode.ACTIVE -> Unit
            Mode.REVOKED -> throw SecurityException("Fixture grant revoked")
            Mode.REMOVED -> throw IOException("Fixture media removed")
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
        .apply { writeBytes(bytes) }

    data class Node(
        val parentId: String?,
        val document: SafDocument,
        val bytes: ByteArray? = null,
    )

    companion object {
        const val AUTHORITY = "com.jedon.kellikanvas.source.saf.test.documents"
        const val ROOT_ID = "root"
        const val LANDSCAPE_ID = "folder-landscape"
        const val PORTRAIT_ID = "folder-portrait"
        const val COVER_ID = "photo-cover"
        const val MOUNTAIN_ID = "photo-mountain"
        const val PERSON_ID = "photo-person"

        val TREE_URI: Uri = Uri.parse("content://$AUTHORITY/tree/$ROOT_ID")
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
                    document = document(LANDSCAPE_ID, "Landscape", DocumentsContract.Document.MIME_TYPE_DIR),
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document(PORTRAIT_ID, "Portrait", DocumentsContract.Document.MIME_TYPE_DIR),
                ),
                Node(
                    parentId = ROOT_ID,
                    document = document(COVER_ID, "Welcome", "image/jpeg", COVER_BYTES.size.toLong()),
                    bytes = COVER_BYTES,
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

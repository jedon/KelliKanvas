package com.jedon.kellikanvas.source.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class AndroidTestDocumentsProvider : DocumentsProvider() {
    enum class Mode {
        ACTIVE,
        REVOKED,
        REMOVED,
    }

    @Volatile
    private var mode: Mode = Mode.ACTIVE

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        require(method == METHOD_SET_MODE || method == METHOD_GET_MODE) {
            "Unsupported instrumentation provider control method"
        }
        val ownerPackage = requireNotNull(context).packageName
        require(callingPackage == ownerPackage) {
            "Instrumentation provider control requires its owner package"
        }
        require(Binder.getCallingUid() == Process.myUid()) {
            "Instrumentation provider control requires its owner UID"
        }
        require(extras == null || extras.isEmpty) {
            "Instrumentation provider control does not accept extras"
        }
        if (method == METHOD_SET_MODE) {
            mode =
                requireNotNull(Mode.entries.singleOrNull { it.name == arg }) {
                    "Unsupported instrumentation provider mode"
                }
        } else {
            require(arg == null) { "Mode read-back does not accept an argument" }
        }
        return Bundle().apply {
            putBoolean(EXTRA_SUCCESS, true)
            putString(EXTRA_MODE, mode.name)
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            addRow(
                columns.map {
                    when (it) {
                        DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                        DocumentsContract.Root.COLUMN_TITLE -> "Instrumentation fixture"
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
        checkMode()
        val columns = projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            NODES[documentId]?.let { addDocument(columns, it) }
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
        checkMode()
        if (mode != "r") throw SecurityException("Instrumentation provider is read-only")
        val node = NODES[documentId] ?: throw FileNotFoundException("Missing instrumentation document")
        val bytes = node.bytes ?: throw FileNotFoundException("Instrumentation document is not a file")
        val file =
            File.createTempFile(
                "saf-instrumentation-",
                ".bin",
                requireNotNull(context).cacheDir,
            ).apply { writeBytes(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        var current = NODES[documentId]?.parentId
        while (current != null) {
            if (current == parentDocumentId) return true
            current = NODES[current]?.parentId
        }
        return false
    }

    private fun queryChildren(
        parentDocumentId: String,
        projection: Array<out String>?,
    ): Cursor {
        checkMode()
        val columns = projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            NODES.values
                .filter { it.parentId == parentDocumentId }
                .forEach { addDocument(columns, it) }
        }
    }

    private fun checkMode() {
        when (mode) {
            Mode.ACTIVE -> Unit
            Mode.REVOKED -> throw SecurityException("Instrumentation grant revoked")
            Mode.REMOVED -> throw FileNotFoundException("Instrumentation media removed")
        }
    }

    private fun MatrixCursor.addDocument(
        columns: Array<out String>,
        node: Node,
    ) {
        addRow(
            columns.map {
                when (it) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mimeType
                    DocumentsContract.Document.COLUMN_SIZE -> node.bytes?.size?.toLong()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> MODIFIED_AT
                    DocumentsContract.Document.COLUMN_FLAGS -> 0
                    else -> null
                }
            }.toTypedArray(),
        )
    }

    data class Node(
        val id: String,
        val parentId: String?,
        val name: String,
        val mimeType: String,
        val bytes: ByteArray? = null,
    )

    companion object {
        const val ROOT_ID = "instrumentation-root"
        const val LANDSCAPE_ID = "instrumentation-landscape"
        const val PORTRAIT_ID = "instrumentation-portrait"
        const val PHOTO_ID = "instrumentation-photo"
        const val MODIFIED_AT = 1_700_000_000_000L
        const val METHOD_SET_MODE = "set_mode"
        const val METHOD_GET_MODE = "get_mode"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SUCCESS = "success"

        val PHOTO_BYTES: ByteArray = "instrumentation-photo-bytes".encodeToByteArray()

        private val NODES =
            listOf(
                Node(ROOT_ID, null, "Fixture root", DocumentsContract.Document.MIME_TYPE_DIR),
                Node(PORTRAIT_ID, ROOT_ID, "Portrait", DocumentsContract.Document.MIME_TYPE_DIR),
                Node(LANDSCAPE_ID, ROOT_ID, "Landscape", DocumentsContract.Document.MIME_TYPE_DIR),
                Node(PHOTO_ID, LANDSCAPE_ID, "Mountain", "image/jpeg", PHOTO_BYTES),
                Node("instrumentation-text", ROOT_ID, "Ignored", "text/plain", "text".encodeToByteArray()),
            ).associateBy(Node::id)

        private val ROOT_COLUMNS =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
            )

        private val DOCUMENT_COLUMNS =
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

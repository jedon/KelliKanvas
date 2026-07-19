package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class SafTreeGrant(
    val treeUri: Uri,
    val documentId: String,
    val flags: Int,
) {
    init {
        require(documentId.isNotBlank()) { "SAF document ID must not be blank" }
        require(flags == maskFlags(flags)) { "SAF grant contains unsupported flags" }
        require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            "SAF grants must be read-only"
        }
    }

    override fun toString(): String = "SafTreeGrant(<redacted>)"

    companion object {
        private const val ALLOWED_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        fun persist(
            resolver: ContentResolver,
            treeUri: Uri,
            resultFlags: Int,
        ): SafTreeGrant {
            require(DocumentsContract.isTreeUri(treeUri)) {
                "SAF persist requires a DocumentsContract tree URI"
            }
            val flags = maskFlags(resultFlags)
            require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "Selected SAF tree did not provide read access"
            }
            resolver.takePersistableUriPermission(treeUri, persistableModeFlags(flags))
            return SafTreeGrant(
                treeUri = treeUri,
                documentId = DocumentsContract.getTreeDocumentId(treeUri),
                flags = flags,
            )
        }

        fun maskFlags(flags: Int): Int = flags and ALLOWED_FLAGS

        fun persistableModeFlags(flags: Int): Int = maskFlags(flags) and Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}

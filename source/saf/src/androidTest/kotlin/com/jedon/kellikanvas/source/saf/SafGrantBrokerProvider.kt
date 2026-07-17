package com.jedon.kellikanvas.source.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.DocumentsContract

class SafGrantBrokerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        require(method == METHOD_GRANT || method == METHOD_REVOKE) {
            "Unsupported SAF grant broker method"
        }
        val ownerContext = requireNotNull(context)
        val callerPackage = requireNotNull(callingPackage) { "SAF grant broker requires a calling package" }
        require(arg == callerPackage) { "SAF grant target must match the caller" }
        require(callerPackage != ownerContext.packageName) { "SAF grant broker requires the target app caller" }
        val callerUid = Binder.getCallingUid()
        require(
            ownerContext.packageManager
                .getPackagesForUid(callerUid)
                .orEmpty()
                .contains(callerPackage),
        ) {
            "SAF grant caller package does not match Binder UID"
        }
        require(
            ownerContext.packageManager.checkSignatures(
                ownerContext.packageName,
                callerPackage,
            ) == PackageManager.SIGNATURE_MATCH,
        ) {
            "SAF grant caller must share the test signing identity"
        }

        @Suppress("DEPRECATION")
        val treeUri = extras?.getParcelable(EXTRA_TREE_URI) as? Uri
        require(treeUri == expectedTreeUri(ownerContext.packageName)) {
            "SAF grant broker only accepts its fixture tree"
        }

        val identity = Binder.clearCallingIdentity()
        try {
            when (method) {
                METHOD_GRANT ->
                    ownerContext.grantUriPermission(
                        callerPackage,
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                METHOD_REVOKE ->
                    ownerContext.revokeUriPermission(
                        callerPackage,
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return Bundle().apply { putBoolean(EXTRA_SUCCESS, true) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException()

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

    companion object {
        const val METHOD_GRANT = "grant"
        const val METHOD_REVOKE = "revoke"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_SUCCESS = "success"

        private fun expectedTreeUri(ownerPackage: String): Uri = DocumentsContract
            .buildTreeDocumentUri(
                "$ownerPackage.saf.documents",
                AndroidTestDocumentsProvider.ROOT_ID,
            )
    }
}

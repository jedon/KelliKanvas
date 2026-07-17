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
        require(method == METHOD_GRANT || method == METHOD_REVOKE || method == METHOD_SET_PROVIDER_MODE) {
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

        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                METHOD_GRANT -> {
                    val treeUri = requireFixtureTree(ownerContext.packageName, extras)
                    ownerContext.grantUriPermission(
                        callerPackage,
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                    successBundle()
                }
                METHOD_REVOKE -> {
                    val treeUri = requireFixtureTree(ownerContext.packageName, extras)
                    ownerContext.revokeUriPermission(
                        callerPackage,
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    successBundle()
                }
                METHOD_SET_PROVIDER_MODE -> {
                    val requestedMode = requireNotNull(extras?.getString(EXTRA_PROVIDER_MODE)) {
                        "SAF provider mode is required"
                    }
                    require(AndroidTestDocumentsProvider.Mode.entries.any { it.name == requestedMode }) {
                        "Unsupported SAF provider mode"
                    }
                    val providerUri = Uri.parse("content://${ownerContext.packageName}.saf.documents")
                    val setResult =
                        requireNotNull(
                            ownerContext.contentResolver.call(
                                providerUri,
                                AndroidTestDocumentsProvider.METHOD_SET_MODE,
                                requestedMode,
                                null,
                            ),
                        )
                    require(setResult.getBoolean(AndroidTestDocumentsProvider.EXTRA_SUCCESS)) {
                        "SAF provider rejected mode update"
                    }
                    val readBack =
                        requireNotNull(
                            ownerContext.contentResolver.call(
                                providerUri,
                                AndroidTestDocumentsProvider.METHOD_GET_MODE,
                                null,
                                null,
                            ),
                        )
                    val acknowledgedMode =
                        requireNotNull(readBack.getString(AndroidTestDocumentsProvider.EXTRA_MODE))
                    require(acknowledgedMode == requestedMode) {
                        "SAF provider mode read-back did not match"
                    }
                    successBundle().apply { putString(EXTRA_PROVIDER_MODE, acknowledgedMode) }
                }
                else -> error("Validated SAF broker method was not handled")
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Suppress("DEPRECATION")
    private fun requireFixtureTree(
        ownerPackage: String,
        extras: Bundle?,
    ): Uri {
        val treeUri = extras?.getParcelable(EXTRA_TREE_URI) as? Uri
        require(treeUri == expectedTreeUri(ownerPackage)) {
            "SAF grant broker only accepts its fixture tree"
        }
        return treeUri
    }

    private fun successBundle(): Bundle = Bundle().apply { putBoolean(EXTRA_SUCCESS, true) }

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
        const val METHOD_SET_PROVIDER_MODE = "set_provider_mode"
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_PROVIDER_MODE = "provider_mode"
        const val EXTRA_SUCCESS = "success"

        private fun expectedTreeUri(ownerPackage: String): Uri = DocumentsContract
            .buildTreeDocumentUri(
                "$ownerPackage.saf.documents",
                AndroidTestDocumentsProvider.ROOT_ID,
            )
    }
}

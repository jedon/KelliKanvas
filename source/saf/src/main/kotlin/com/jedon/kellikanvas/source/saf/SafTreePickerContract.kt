package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

class SafTreePickerContract(
    private val resolver: ContentResolver,
) : ActivityResultContract<Uri?, SafTreeGrant?>() {
    private val delegate = ActivityResultContracts.OpenDocumentTree()

    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent = delegate.createIntent(context, input)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): SafTreeGrant? {
        val treeUri = delegate.parseResult(resultCode, intent) ?: return null
        return SafTreeGrant.persist(
            resolver = resolver,
            treeUri = treeUri,
            resultFlags = intent?.flags ?: 0,
        )
    }
}

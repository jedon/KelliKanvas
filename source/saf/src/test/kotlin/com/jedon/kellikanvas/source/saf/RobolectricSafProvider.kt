package com.jedon.kellikanvas.source.saf

import android.Manifest
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

data class RobolectricSafProvider(
    val provider: TestDocumentsProvider,
    val resolver: ContentResolver,
    val treeUri: Uri,
    val providerInfo: ProviderInfo,
)

fun registerSafProvider(): RobolectricSafProvider {
    val authority = "com.jedon.kellikanvas.source.saf.test.documents.${NEXT_AUTHORITY.incrementAndGet()}"
    val application = RuntimeEnvironment.getApplication()
    val providerInfo =
        ProviderInfo().apply {
            this.authority = authority
            name = TestDocumentsProvider::class.java.name
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
            enabled = true
        }
    val provider =
        Robolectric
            .buildContentProvider(TestDocumentsProvider::class.java)
            .create(providerInfo)
            .get()
    return RobolectricSafProvider(
        provider = provider,
        resolver = RuntimeEnvironment.getApplication().contentResolver,
        treeUri = DocumentsContract.buildTreeDocumentUri(authority, TestDocumentsProvider.ROOT_ID),
        providerInfo = providerInfo,
    )
}

private val NEXT_AUTHORITY = AtomicInteger()

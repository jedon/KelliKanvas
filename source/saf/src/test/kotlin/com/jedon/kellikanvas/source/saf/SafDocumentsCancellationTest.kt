package com.jedon.kellikanvas.source.saf

import android.content.Intent
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafDocumentsCancellationTest {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor {
            Thread(it, "saf-cancellation-test").apply { isDaemon = true }
        }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `query cancellation immediately cancels platform signal`() = runTest {
        val fixture = registerBlockingQueryProvider(TestDocumentsProvider.CancellationBehavior.THROW)
        val block = fixture.provider.block
        val documents = fixture.documents(executor)
        val operation =
            async {
                documents.children(fixture.treeUri, TestDocumentsProvider.ROOT_ID)
            }

        block.started.await()
        val expected = CancellationException("cancel query")
        operation.cancel(expected)
        block.signalCancelled.await()
        block.finished.await()

        assertThat(operation.isCancelled).isTrue()
    }

    @Test
    fun `platform operation cancellation maps to coroutine cancellation with cause`() = runTest {
        val fixture = registerBlockingQueryProvider(TestDocumentsProvider.CancellationBehavior.THROW)
        fixture.provider.cancelImmediately = true
        val documents = fixture.documents(executor)

        val failure =
            try {
                documents.children(fixture.treeUri, TestDocumentsProvider.ROOT_ID)
                throw AssertionError("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertThat(failure.hasCause<OperationCanceledException>()).isTrue()
    }

    @Test
    fun `cursor returned after cancellation is closed`() = runTest {
        val fixture =
            registerBlockingQueryProvider(
                TestDocumentsProvider.CancellationBehavior.RETURN_RESOURCE,
            )
        val block = fixture.provider.block
        val documents = fixture.documents(executor)
        val operation =
            async {
                documents.children(fixture.treeUri, TestDocumentsProvider.ROOT_ID)
            }

        block.started.await()
        operation.cancel(CancellationException("cancel query race"))
        block.signalCancelled.await()
        block.release()
        block.finished.await()
        operation.join()
        awaitExecutorIdle()

        assertThat(fixture.provider.closedCursorCount.get()).isEqualTo(1)
    }

    @Test
    fun `cancellation during cursor parsing closes cursor`() = runTest {
        val fixture = registerSafProvider()
        val documents = ContentResolverSafDocuments(fixture.resolver, executor)
        lateinit var operation: Job
        fixture.provider.onCursorMove.set {
            operation.cancel(CancellationException("cancel cursor parsing"))
        }
        operation =
            launch(start = CoroutineStart.LAZY) {
                documents.children(fixture.treeUri, TestDocumentsProvider.ROOT_ID)
            }

        operation.start()
        operation.join()

        assertThat(operation.isCancelled).isTrue()
        assertThat(fixture.provider.closedCursorCount.get()).isEqualTo(1)
    }

    @Test
    fun `descriptor open cancellation immediately cancels platform signal`() = runTest {
        val fixture = registerSafProvider()
        val block = TestDocumentsProvider.BlockingCall(TestDocumentsProvider.CancellationBehavior.THROW)
        val documents = descriptorDocuments(fixture, block)
        val operation =
            async {
                documents.openRead(fixture.treeUri, TestDocumentsProvider.COVER_ID)
            }

        block.started.await()
        operation.cancel(CancellationException("cancel descriptor open"))
        block.signalCancelled.await()
        block.finished.await()

        assertThat(operation.isCancelled).isTrue()
    }

    @Test
    fun `descriptor returned after cancellation is closed`() = runTest {
        val fixture = registerSafProvider()
        val block = TestDocumentsProvider.BlockingCall(TestDocumentsProvider.CancellationBehavior.RETURN_RESOURCE)
        val documents = descriptorDocuments(fixture, block)
        val operation =
            async {
                documents.openRead(fixture.treeUri, TestDocumentsProvider.COVER_ID)
            }

        block.started.await()
        operation.cancel(CancellationException("cancel descriptor race"))
        block.signalCancelled.await()
        block.release()
        block.finished.await()
        operation.join()
        awaitExecutorIdle()

        assertThat(fixture.provider.lastOpenedDescriptor.get()?.fileDescriptor?.valid()).isFalse()
    }

    @Test
    fun `cancellation before stream construction closes delivered descriptor`() = runTest {
        val fixture = registerSafProvider()
        val closed = AtomicInteger()
        lateinit var operation: Job
        val observer =
            object : SafReadObserver {
                override fun onOpen(documentId: String) {
                    operation.cancel(CancellationException("cancel stream construction"))
                }

                override fun onClose(documentId: String) {
                    closed.incrementAndGet()
                }
            }
        val profile =
            SafProfile(
                id = SourceProfileId("stream-construction-profile"),
                grant = SafTreeGrant(
                    treeUri = fixture.treeUri,
                    documentId = TestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        val adapter =
            SafSourceAdapter(
                profile,
                ContentResolverSafDocuments(fixture.resolver, executor, observer),
            )
        val asset =
            AssetRef(
                profileId = profile.id,
                objectId = ProviderObjectId(TestDocumentsProvider.COVER_ID),
                mimeType = "image/jpeg",
                byteLength = TestDocumentsProvider.COVER_BYTES.size.toLong(),
            )
        operation =
            launch(start = CoroutineStart.LAZY) {
                adapter.open(asset)
            }

        operation.start()
        operation.join()

        assertThat(operation.isCancelled).isTrue()
        assertThat(closed.get()).isEqualTo(1)
        assertThat(fixture.provider.lastOpenedDescriptor.get()?.fileDescriptor?.valid()).isFalse()
    }

    private fun awaitExecutorIdle() {
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun descriptorDocuments(
        fixture: RobolectricSafProvider,
        block: TestDocumentsProvider.BlockingCall,
    ): ContentResolverSafDocuments = ContentResolverSafDocuments(
        resolver = fixture.resolver,
        ioExecutor = executor,
        openDescriptor = { _, signal ->
            block.await(signal)
            ParcelFileDescriptor
                .open(descriptorPayload(), ParcelFileDescriptor.MODE_READ_ONLY)
                .also(fixture.provider.lastOpenedDescriptor::set)
        },
    )

    private fun descriptorPayload(): File = File.createTempFile("saf-cancellation-", ".bin").apply {
        writeBytes(TestDocumentsProvider.COVER_BYTES)
        deleteOnExit()
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

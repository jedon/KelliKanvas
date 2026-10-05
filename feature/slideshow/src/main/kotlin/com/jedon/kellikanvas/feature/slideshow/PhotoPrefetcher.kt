package com.jedon.kellikanvas.feature.slideshow

import android.graphics.Bitmap
import com.jedon.kellikanvas.model.AssetRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns exactly one pending photo in addition to the caller's displayed photo. Main-thread confined. */
internal class PhotoPrefetcher(private val scope: CoroutineScope, private val decode: suspend (AssetRef) -> Bitmap) {
    private var pending: Slot? = null
    private var closed = false

    private inner class Slot(val asset: AssetRef) {
        val ready = CompletableDeferred<Unit>()
        var bitmap: Bitmap? = null
        var failure: Throwable? = null
        val job: Job = scope.launch {
            try {
                bitmap = decode(asset)
                ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (!isActive || closed) {
                    bitmap?.recycle()
                    bitmap = null
                }
                ready.complete(Unit)
            }
        }
        fun discard() {
            job.cancel()
            bitmap?.recycle()
            bitmap = null
        }
    }

    suspend fun prefetch(asset: AssetRef) {
        check(!closed)
        if (pending?.asset == asset) return
        val previous = pending
        pending = null
        previous?.discard()
        previous?.job?.cancelAndJoin()
        pending = Slot(asset)
    }

    suspend fun take(asset: AssetRef): Bitmap {
        prefetch(asset)
        val slot = requireNotNull(pending)
        slot.ready.await()
        pending = null
        slot.failure?.let { throw it }
        return requireNotNull(slot.bitmap) { "Photo decode was cancelled" }.also { slot.bitmap = null }
    }

    fun close() {
        closed = true
        pending?.discard()
        pending = null
    }
}

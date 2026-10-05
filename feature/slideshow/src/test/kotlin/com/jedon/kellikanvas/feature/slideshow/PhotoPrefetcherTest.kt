package com.jedon.kellikanvas.feature.slideshow

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoPrefetcherTest {
    private fun asset(id: String) = AssetRef(SourceProfileId("nas"), ProviderObjectId(id), "image/jpeg")
    private fun bitmap() = Bitmap.createBitmap(384, 216, Bitmap.Config.RGB_565)

    @Test fun nextPhotoStartsInBackgroundAndIsReusedWithoutAnotherRead() = runTest {
        val released = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val next = bitmap()
        val current = bitmap()
        val buffer = PhotoPrefetcher(this) { photo ->
            reads += photo.objectId.value
            released.await()
            next
        }
        buffer.prefetch(asset("next"))
        runCurrent()
        assertThat(reads).containsExactly("next")
        val take = async { buffer.take(asset("next")) }
        runCurrent()
        assertThat(take.isCompleted).isFalse()
        assertThat(current.isRecycled).isFalse()
        released.complete(Unit)
        assertThat(take.await()).isSameInstanceAs(next)
        assertThat(reads).containsExactly("next")
        buffer.close()
        assertThat(next.isRecycled).isFalse() // Ownership was transferred to the displayed frame.
        current.recycle()
        next.recycle()
    }

    @Test fun manualSkipDiscardsQueuedFrameAndExitRecyclesOnlyUnclaimedFrame() = runTest {
        val discarded = bitmap()
        val target = bitmap()
        val queued = bitmap()
        val buffer = PhotoPrefetcher(this) { photo ->
            when (photo.objectId.value) {
                "next" -> discarded
                "previous" -> target
                else -> queued
            }
        }
        buffer.prefetch(asset("next"))
        runCurrent()
        assertThat(buffer.take(asset("previous"))).isSameInstanceAs(target)
        assertThat(discarded.isRecycled).isTrue()
        buffer.prefetch(asset("queued"))
        runCurrent()
        buffer.close()
        assertThat(queued.isRecycled).isTrue()
        assertThat(target.isRecycled).isFalse()
        target.recycle()
    }

    @Test fun replacementCancelsAndClosesSlowReadBeforeAnotherDecodeStarts() = runTest {
        var opened = 0
        var maximum = 0
        val frame = bitmap()
        val buffer = PhotoPrefetcher(this) { photo ->
            opened++
            maximum = maxOf(maximum, opened)
            try {
                if (photo.objectId.value == "slow") awaitCancellation() else frame
            } finally {
                opened--
            }
        }
        buffer.prefetch(asset("slow"))
        runCurrent()
        assertThat(buffer.take(asset("skip"))).isSameInstanceAs(frame)
        assertThat(maximum).isEqualTo(1)
        assertThat(opened).isEqualTo(0)
        buffer.close()
        frame.recycle()
    }

    @Test fun failedPrefetchDoesNotPreventReadingAnotherPhoto() = runTest {
        val frame = bitmap()
        val buffer = PhotoPrefetcher(this) { photo -> if (photo.objectId.value == "broken") throw IOException("unreadable") else frame }
        buffer.prefetch(asset("broken"))
        runCurrent()
        val result = runCatching { buffer.take(asset("broken")) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(buffer.take(asset("good"))).isSameInstanceAs(frame)
        buffer.close()
        frame.recycle()
    }
}

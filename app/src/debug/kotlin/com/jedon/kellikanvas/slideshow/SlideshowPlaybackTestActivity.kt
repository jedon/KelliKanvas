package com.jedon.kellikanvas.slideshow

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.graphics.createBitmap
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.slideshow.SimpleSlideshowScreen
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.model.TransitionType
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.ui.tv.KanvasTheme
import kotlinx.coroutines.CompletableDeferred
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Isolated slow NAS fixture for actual slideshow/compositor tests; excluded from release APKs. */
class SlideshowPlaybackTestActivity : ComponentActivity() {
    val releaseSecond = CompletableDeferred<Unit>()
    val reads = ConcurrentHashMap<String, AtomicInteger>()
    private val profile = SourceProfileId("slideshow-test-nas")
    private fun photo(id: String) = AssetRef(profile, ProviderObjectId(id), "image/png")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val photos = listOf("first", "second", "broken", "fourth").map { SourceEntry.Photo(photo(it), "$it.png") }
        val payloads = mapOf("first" to image(Color.RED), "second" to image(Color.GREEN), "fourth" to image(Color.YELLOW))
        val adapter = object : SourceAdapter() {
            override val profileId = profile
            override val kind = SourceKind.SMB
            override val capabilities = SourceCapabilities()
            override suspend fun probe() = SourceStatus(true, "Test NAS")
            override suspend fun listChildrenPage(folder: FolderRef, cursor: PageCursor?, limit: Int) = Page<SourceEntry>(photos, null)
            override suspend fun metadataFor(asset: AssetRef) = PhotoMetadata(asset)
            override suspend fun openStream(asset: AssetRef): PhotoByteStream {
                val id = asset.objectId.value
                reads.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                if (id == "second") releaseSecond.await()
                if (id == "broken") throw IOException("Unreadable fixture photo")
                val bytes = payloads.getValue(id)
                return object : PhotoByteStream(bytes.size.toLong()) {
                    private var offset = 0
                    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                        if (offset >= bytes.size) return -1
                        val count = minOf(byteCount.toInt(), bytes.size - offset)
                        sink.write(bytes, offset, count)
                        offset += count
                        return count.toLong()
                    }
                    override fun close() = Unit
                }
            }
        }
        setContent {
            KanvasTheme {
                SimpleSlideshowScreen(
                    adapters = mapOf(profile to adapter),
                    roots = listOf(SelectedRoot("default", profile, ProviderObjectId("root"), "Test NAS", false)),
                    onExit = {},
                    slideDurationMillis = 3_000,
                    transitionType = TransitionType.CROSSFADE,
                    transitionDurationMillis = 700,
                    maxEdgePx = 384,
                )
            }
        }
    }
    private fun image(color: Int): ByteArray {
        val bitmap = createBitmap(384, 216, Bitmap.Config.RGB_565).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }
}

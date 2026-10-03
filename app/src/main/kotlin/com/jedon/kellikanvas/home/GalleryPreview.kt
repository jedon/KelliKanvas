package com.jedon.kellikanvas.home

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.slideshow.PhotoBitmapLoader
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class GalleryPreview(val image: ImageBitmap? = null, val loading: Boolean = false)

/** Preview work is bounded and never scans a full NAS collection just to render Home. */
internal suspend fun findGalleryPreviewAssets(
    adapters: Map<SourceProfileId, SourceAdapter>,
    roots: List<SelectedRoot>,
): List<AssetRef> {
    data class Visit(
        val folder: FolderRef,
        val recursive: Boolean,
        val filters: Set<String>,
        val depth: Int = 0,
        val cursor: PageCursor? = null,
    )
    val pending = ArrayDeque(roots.map { Visit(FolderRef(it.profileId, it.objectId), it.includeDescendants, it.fileTypeFilters) })
    val visited = mutableSetOf<Visit>()
    val photos = mutableListOf<AssetRef>()
    var requests = 0
    while (pending.isNotEmpty() && requests < 12 && photos.size < 3) {
        val visit = pending.removeFirst()
        if (!visited.add(visit)) continue
        val adapter = adapters[visit.folder.profileId] ?: continue
        requests++
        val page = try {
            adapter.listChildren(visit.folder, visit.cursor, 24)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            continue
        }
        page.items.forEach { entry ->
            when (entry) {
                is SourceEntry.Photo -> {
                    val allowed = visit.filters
                    if ((allowed.isEmpty() || entry.asset.mimeType in allowed) && photos.size < 3) photos += entry.asset
                }
                is SourceEntry.Folder -> if (visit.recursive && visit.depth < 3) {
                    pending.addLast(Visit(entry.ref, true, visit.filters, visit.depth + 1))
                }
            }
        }
        page.nextCursor?.let { pending.addLast(visit.copy(cursor = it)) }
    }
    return photos.distinctBy { it.key }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun rememberGalleryPreview(adapters: Map<SourceProfileId, SourceAdapter>, roots: List<SelectedRoot>): GalleryPreview {
    var preview by remember(adapters, roots) { mutableStateOf(GalleryPreview(loading = roots.isNotEmpty())) }
    LaunchedEffect(adapters, roots) {
        if (roots.isEmpty() || adapters.isEmpty()) {
            preview = GalleryPreview()
            return@LaunchedEffect
        }
        // A single small bitmap is owned by Compose/GC. Do not recycle it while a render frame can still use it.
        val bitmap = withTimeoutOrNull(12_000) {
            withContext(Dispatchers.IO) {
                var decoded: Bitmap? = null
                for (asset in findGalleryPreviewAssets(adapters, roots)) {
                    try {
                        decoded = PhotoBitmapLoader.decode(adapters.getValue(asset.profileId).open(asset), 960)
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Try the next candidate when the first photo is unavailable or malformed.
                    }
                }
                decoded
            }
        }
        preview = GalleryPreview(bitmap?.asImageBitmap())
    }
    return preview
}

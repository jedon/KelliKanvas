package com.jedon.kellikanvas.feature.slideshow

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.renderer.surface.DisplayPhotoTarget
import com.jedon.kellikanvas.renderer.surface.PhotoSurfaceView
import com.jedon.kellikanvas.renderer.surface.isTelevisionFormFactor
import com.jedon.kellikanvas.renderer.surface.slideshowDecodeLongEdgePx
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.ui.tv.KanvasColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "SimpleSlideshow"
private const val DECODE_ERROR_DWELL_MS = 1_200L

@Suppress("ktlint:standard:function-naming")
@Composable
fun SimpleSlideshowScreen(
    adapters: Map<SourceProfileId, SourceAdapter>,
    roots: List<SelectedRoot>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    slideDurationMillis: Long = 15_000,
    maxEdgePx: Int? = null,
    onRootFailures: (List<String>) -> Unit = {},
) {
    val context = LocalContext.current
    // Panel-sized decode: 4K TV → 3840 long edge. Never default to an arbitrary 1920 OOM band-aid.
    val resolvedMaxEdge =
        maxEdgePx
            ?: remember(context) { context.slideshowDecodeLongEdgePx() }
    val television = remember(context) { context.isTelevisionFormFactor() }
    val focusRequester = remember { FocusRequester() }
    var playlist by remember { mutableStateOf<List<AssetRef>?>(null) }
    var player by remember { mutableStateOf<SlideshowPlayerState?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var surfaceView by remember { mutableStateOf<PhotoSurfaceView?>(null) }
    var loadFailure by remember { mutableStateOf(false) }
    var rootFailureMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var photoLoadError by remember { mutableStateOf<String?>(null) }
    var consecutiveDecodeFailures by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var firstFrameShown by remember { mutableStateOf(false) }

    LaunchedEffect(bitmap != null) {
        if (bitmap != null && !firstFrameShown) {
            firstFrameShown = true
            controlsVisible = true
            interactionRevision++
        }
    }

    fun interact(action: () -> Unit) {
        action()
        controlsVisible = true
        interactionRevision++
    }

    LaunchedEffect(interactionRevision, player?.playing) {
        if (player?.playing != false) {
            delay(4_000)
            controlsVisible = false
        }
    }

    BackHandler(onBack = onExit)
    DisposableEffect(Unit) {
        onDispose {
            surfaceView?.clearFrame()
            bitmap?.recycle()
            bitmap = null
        }
    }
    LaunchedEffect(Unit) {
        val buildResult =
            runUnlessCancelled { CollectionPhotoPlaylist.build(adapters, roots) }.getOrElse {
                loadFailure = true
                CollectionPlaylistResult(photos = emptyList(), rootOutcomes = emptyList())
            }
        playlist = buildResult.photos
        rootFailureMessages = buildResult.failedRoots.map { it.userMessage() }
        onRootFailures(rootFailureMessages)
        playlist?.takeIf { it.isNotEmpty() }?.let {
            player = SlideshowPlayerState(it.size, slideDurationMillis)
        }
        focusRequester.requestFocus()
    }
    LaunchedEffect(player?.playing, player?.index, playlist, bitmap, photoLoadError) {
        val activePlayer = player ?: return@LaunchedEffect
        val activePlaylist = playlist ?: return@LaunchedEffect
        if (activePlaylist.isEmpty() || !activePlayer.playing) return@LaunchedEffect
        // Advance only while a photo is visible; decode failures skip via their own dwell.
        if (photoLoadError != null || bitmap == null) return@LaunchedEffect
        delay(activePlayer.intervalMillis)
        activePlayer.next()
    }
    LaunchedEffect(adapters, roots) {
        val googleRoots = roots.filter { adapters[it.profileId]?.kind == SourceKind.GOOGLE_PHOTOS }
        if (googleRoots.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(10 * 60_000L)
            val current = playlist ?: continue
            val activePlayer = player ?: continue
            val refreshed = CollectionPhotoPlaylist.build(adapters, googleRoots)
            val successfulProfiles = refreshed.rootOutcomes.filterIsInstance<PlaylistRootOutcome.Loaded>().map { it.root.profileId }.toSet()
            if (successfulProfiles.isEmpty()) continue
            val activeKey = current.getOrNull(activePlayer.index)?.key
            val updated = (current.filterNot { it.profileId in successfulProfiles } + refreshed.photos).distinctBy { it.key }
            if (updated.isNotEmpty()) {
                activePlayer.updatePlaylistSize(updated.size, updated.indexOfFirst { it.key == activeKey }.coerceAtLeast(0))
                playlist = updated
            }
        }
    }
    LaunchedEffect(player?.index, playlist, resolvedMaxEdge) {
        val activePlaylist = playlist ?: return@LaunchedEffect
        val activePlayer = player ?: return@LaunchedEffect
        val index = activePlayer.index
        val asset = activePlaylist.getOrNull(index) ?: return@LaunchedEffect
        photoLoadError = null
        // Drop the previous frame before decoding the next so we never hold two panel-sized
        // bitmaps plus the compressed PNG bytes at once.
        val previous = bitmap
        bitmap = null
        surfaceView?.clearFrame()
        previous?.recycle()
        val result =
            runUnlessCancelled {
                PhotoBitmapLoader.decode(
                    adapters.getValue(asset.profileId).open(asset),
                    resolvedMaxEdge,
                )
            }
        val decoded = result.getOrNull()
        bitmap = decoded
        if (decoded != null) {
            surfaceView?.showFrame(decoded)
        }
        if (decoded == null) {
            val reason =
                result.exceptionOrNull()?.let { failure ->
                    DiagLog.w(TAG, "Decode failed for ${asset.objectId.value}", failure)
                    briefErrorReason(failure)
                } ?: "Unable to decode"
            photoLoadError = reason
            consecutiveDecodeFailures += 1
            if (consecutiveDecodeFailures >= activePlaylist.size) {
                // Every item failed — stay on the error so Back can exit.
                return@LaunchedEffect
            }
            delay(DECODE_ERROR_DWELL_MS)
            if (player?.index == index) {
                activePlayer.next()
            }
        } else {
            consecutiveDecodeFailures = 0
            photoLoadError = null
        }
    }

    val contentModifier = modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusRequester(focusRequester)
        .focusable()
        .pointerInput(player) {
            detectTapGestures { position ->
                val activePlayer = player ?: return@detectTapGestures
                when {
                    position.x < size.width / 3f -> interact { activePlayer.prev() }
                    position.x > size.width * 2f / 3f -> interact { activePlayer.next() }
                    else -> interact { activePlayer.togglePause() }
                }
            }
        }
    Box(
        modifier = contentModifier
            .onKeyAction(Key.DirectionLeft) { interact { player?.prev() } }
            .onKeyAction(Key.DirectionRight) { interact { player?.next() } }
            .onKeyAction(Key.DirectionCenter) { interact { player?.togglePause() } }
            .onKeyAction(Key.Enter) { interact { player?.togglePause() } }
            .onKeyAction(Key.NumPadEnter) { interact { player?.togglePause() } },
        contentAlignment = Alignment.Center,
    ) {
        // SurfaceView owns still-photo pixels (panel-sized buffer). Overlay text for status.
        // Video never uses this Compose/ARGB path — MediaCodec → Surface; stills must match.
        AndroidView(
            factory = { ctx ->
                PhotoSurfaceView(ctx).also { view ->
                    view.layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    if (television && resolvedMaxEdge >= DisplayPhotoTarget.UHD_WIDTH) {
                        view.setFixedPanelSize(
                            DisplayPhotoTarget.UHD_WIDTH,
                            DisplayPhotoTarget.UHD_HEIGHT,
                        )
                    }
                    surfaceView = view
                    bitmap?.let { view.showFrame(it) }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                surfaceView = view
                val frame = bitmap
                if (frame != null && !frame.isRecycled) {
                    view.showFrame(frame)
                } else {
                    view.clearFrame()
                }
            },
        )
        if (controlsVisible && bitmap != null && photoLoadError == null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                color = KanvasColors.Background.copy(alpha = .92f),
                contentColor = KanvasColors.Text,
                shape = RoundedCornerShape(12.dp),
            ) {
                BoxWithConstraints {
                    if (maxWidth < 600.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${if (player?.playing == false) "Paused" else "Slideshow"}  ·  ${(player?.index ?: 0) + 1} / ${playlist?.size ?: 0}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Tap left or right to browse. Tap the center to ${if (player?.playing == false) "play" else "pause"}.",
                                color = KanvasColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (player?.playing == false) "Paused" else "Slideshow", style = MaterialTheme.typography.titleSmall)
                            Text("${(player?.index ?: 0) + 1} / ${playlist?.size ?: 0}", color = KanvasColors.Accent)
                            Text(
                                "← →  Browse     OK  ${if (player?.playing == false) "Play" else "Pause"}     Back  Gallery",
                                color = KanvasColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        when {
            playlist == null -> Text(text = "Loading…", color = Color.White)
            playlist?.isEmpty() == true ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = when {
                            loadFailure -> "Unable to load slideshow"
                            rootFailureMessages.isNotEmpty() -> "Couldn't load photos from your folders"
                            else -> "No photos in this collection"
                        },
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    rootFailureMessages.forEach { message ->
                        Text(
                            text = message,
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            photoLoadError != null ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = "Unable to display this photo",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = photoLoadError.orEmpty(),
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            bitmap == null -> Text(text = "Loading photo…", color = Color.White)
        }
    }
}

/**
 * [runCatching] treats [CancellationException] as a failure. Slideshow load and decode
 * must abort when the user skips, or a cancel looks like a broken photo and can stop
 * the show after enough skips.
 */
private inline fun <T> runUnlessCancelled(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

internal fun briefErrorReason(failure: Throwable): String {
    val raw =
        failure.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: failure::class.simpleName
            ?: "Decode error"
    return raw.take(96)
}

private fun Modifier.onKeyAction(
    expectedKey: Key,
    action: () -> Unit,
): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyUp && event.key == expectedKey) {
        action()
        true
    } else {
        false
    }
}

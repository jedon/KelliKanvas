package com.jedon.kellikanvas.feature.slideshow

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.renderer.surface.DisplayPhotoTarget
import com.jedon.kellikanvas.renderer.surface.PhotoSurfaceView
import com.jedon.kellikanvas.renderer.surface.isTelevisionFormFactor
import com.jedon.kellikanvas.renderer.surface.slideshowDecodeLongEdgePx
import com.jedon.kellikanvas.source.SourceAdapter
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
            runCatching { CollectionPhotoPlaylist.build(adapters, roots) }.getOrElse {
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
            runCatching {
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
                    position.x < size.width / 3f -> activePlayer.prev()
                    position.x > size.width * 2f / 3f -> activePlayer.next()
                    else -> activePlayer.togglePause()
                }
            }
        }
    Box(
        modifier = contentModifier
            .onKeyAction(Key.DirectionLeft) { player?.prev() }
            .onKeyAction(Key.DirectionRight) { player?.next() }
            .onKeyAction(Key.DirectionCenter) { player?.togglePause() }
            .onKeyAction(Key.Enter) { player?.togglePause() }
            .onKeyAction(Key.NumPadEnter) { player?.togglePause() },
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

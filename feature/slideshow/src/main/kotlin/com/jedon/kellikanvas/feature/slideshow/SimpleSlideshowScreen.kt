package com.jedon.kellikanvas.feature.slideshow

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.delay

private const val TAG = "SimpleSlideshow"
private const val DECODE_ERROR_DWELL_MS = 1_200L

@Suppress("ktlint:standard:function-naming")
@Composable
fun SimpleSlideshowScreen(
    adapters: Map<SourceProfileId, SourceAdapter>,
    roots: List<SelectedRoot>,
    slideDurationMillis: Long = 15_000,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = 1_920,
) {
    val focusRequester = remember { FocusRequester() }
    var playlist by remember { mutableStateOf<List<AssetRef>?>(null) }
    var player by remember { mutableStateOf<SlideshowPlayerState?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadFailure by remember { mutableStateOf(false) }
    var photoLoadError by remember { mutableStateOf<String?>(null) }
    var consecutiveDecodeFailures by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onExit)
    DisposableEffect(Unit) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }
    LaunchedEffect(Unit) {
        playlist = runCatching { CollectionPhotoPlaylist.build(adapters, roots) }.getOrElse {
            loadFailure = true
            emptyList()
        }
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
    LaunchedEffect(player?.index, playlist) {
        val activePlaylist = playlist ?: return@LaunchedEffect
        val activePlayer = player ?: return@LaunchedEffect
        val index = activePlayer.index
        val asset = activePlaylist.getOrNull(index) ?: return@LaunchedEffect
        photoLoadError = null
        val result =
            runCatching {
                PhotoBitmapLoader.decode(
                    adapters.getValue(asset.profileId).open(asset),
                    maxEdgePx,
                )
            }
        val decoded = result.getOrNull()
        val previous = bitmap
        bitmap = decoded
        previous?.recycle()
        if (decoded == null) {
            val reason =
                result.exceptionOrNull()?.let { failure ->
                    Log.w(TAG, "Decode failed for ${asset.objectId.value}", failure)
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
        when {
            playlist == null -> Text(text = "Loading…", color = Color.White)
            playlist?.isEmpty() == true -> Text(
                text = if (loadFailure) "Unable to load slideshow" else "No photos in this collection",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
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
            else -> {
                val image = bitmap ?: return@Box
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Slideshow photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (image.height > image.width) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                )
            }
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

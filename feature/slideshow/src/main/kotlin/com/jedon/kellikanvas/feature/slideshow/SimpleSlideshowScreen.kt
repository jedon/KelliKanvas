package com.jedon.kellikanvas.feature.slideshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.tv.material3.Text
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.delay

@Composable
fun SimpleSlideshowScreen(
    adapter: SourceAdapter,
    roots: List<SelectedRoot>,
    slideDurationMillis: Long = 15_000,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = 2_048,
) {
    val focusRequester = remember { FocusRequester() }
    var playlist by remember { mutableStateOf<List<AssetRef>?>(null) }
    var player by remember { mutableStateOf<SlideshowPlayerState?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadFailure by remember { mutableStateOf(false) }

    BackHandler(onBack = onExit)
    LaunchedEffect(Unit) {
        playlist = runCatching { SafPhotoPlaylist.build(adapter, roots) }.getOrElse {
            loadFailure = true
            emptyList()
        }
        playlist?.takeIf { it.isNotEmpty() }?.let {
            player = SlideshowPlayerState(it.size, slideDurationMillis)
        }
        focusRequester.requestFocus()
    }
    LaunchedEffect(player?.playing, player?.index, playlist) {
        val activePlayer = player ?: return@LaunchedEffect
        val activePlaylist = playlist ?: return@LaunchedEffect
        if (activePlaylist.isEmpty() || !activePlayer.playing) return@LaunchedEffect
        delay(activePlayer.intervalMillis)
        activePlayer.next()
    }
    LaunchedEffect(player?.index, playlist) {
        val activePlaylist = playlist ?: return@LaunchedEffect
        val activePlayer = player ?: return@LaunchedEffect
        val asset = activePlaylist.getOrNull(activePlayer.index) ?: return@LaunchedEffect
        bitmap = runCatching {
            PhotoBitmapLoader.decode(adapter.open(asset), maxEdgePx)
        }.getOrNull()
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
            .onKeyAction(Key.DirectionCenter) { player?.togglePause() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            playlist == null -> Text(text = "Loading…", color = Color.White)
            playlist?.isEmpty() == true -> Text(
                text = if (loadFailure) "Unable to load slideshow" else "No photos in this collection",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
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

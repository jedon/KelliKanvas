package com.jedon.kellikanvas.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** A quiet framed study while a collection preview is unavailable; never presented as a user photo. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun GalleryArtwork(preview: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1.6f).background(Color(0xFF948C7D), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFFB1A894), RoundedCornerShape(4.dp)).padding(5.dp)
            .background(Color(0xFFE1DCD0)).padding(18.dp),
    ) {
        if (preview != null) {
            Image(
                preview,
                contentDescription = "Preview from your photo collection",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFFBCC8BE))
                drawCircle(Color(0xFFE6DFC7), size.height * .105f, Offset(size.width * .72f, size.height * .28f))
                fun ridge(color: Color, points: List<Pair<Float, Float>>) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        points.forEach { (x, y) -> lineTo(x * size.width, y * size.height) }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, color)
                }
                ridge(Color(0xFF869F94), listOf(0f to .62f, .18f to .38f, .34f to .56f, .52f to .34f, .76f to .58f, 1f to .4f))
                ridge(Color(0xFF516E62), listOf(0f to .7f, .22f to .6f, .5f to .76f, .72f to .54f, 1f to .72f))
                ridge(Color(0xFF314D40), listOf(0f to .84f, .22f to .77f, .48f to .9f, .8f to .8f, 1f to .87f))
            }
        }
    }
}

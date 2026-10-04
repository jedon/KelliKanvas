package com.jedon.kellikanvas.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.model.AppTheme
import com.jedon.kellikanvas.ui.tv.KanvasBat
import com.jedon.kellikanvas.ui.tv.LocalKanvasTheme

/** A quiet framed study while a collection preview is unavailable; never presented as a user photo. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun GalleryArtwork(preview: ImageBitmap?, modifier: Modifier = Modifier) {
    val kelli = LocalKanvasTheme.current == AppTheme.KELLI
    val frame = if (kelli) Color(0xFF947135) else Color(0xFF948C7D)
    val edge = if (kelli) Color(0xFFE8C76B) else Color(0xFFB1A894)
    val mat = if (kelli) Color(0xFF100B17) else Color(0xFFE1DCD0)
    Box(
        modifier.aspectRatio(1.6f).background(frame, RoundedCornerShape(4.dp))
            .border(1.dp, edge, RoundedCornerShape(4.dp)).padding(5.dp)
            .background(mat).padding(18.dp),
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
                if (kelli) {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF120D22), Color(0xFF4C2B6A))))
                } else {
                    drawRect(Color(0xFFBCC8BE))
                }
                drawCircle(if (kelli) Color(0xFFE8C76B) else Color(0xFFE6DFC7), size.height * .105f, Offset(size.width * .72f, size.height * .28f))
                fun ridge(color: Color, points: List<Pair<Float, Float>>) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        points.forEach { (x, y) -> lineTo(x * size.width, y * size.height) }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, color)
                }
                ridge(if (kelli) Color(0xFF5B3B79) else Color(0xFF869F94), listOf(0f to .62f, .18f to .38f, .34f to .56f, .52f to .34f, .76f to .58f, 1f to .4f))
                ridge(if (kelli) Color(0xFF352347) else Color(0xFF516E62), listOf(0f to .7f, .22f to .6f, .5f to .76f, .72f to .54f, 1f to .72f))
                ridge(if (kelli) Color(0xFF160F21) else Color(0xFF314D40), listOf(0f to .84f, .22f to .77f, .48f to .9f, .8f to .8f, 1f to .87f))
                if (kelli) {
                    // Viewfinder corners suggest photography without competing with the artwork.
                    val inset = 12.dp.toPx()
                    val length = 18.dp.toPx()
                    val color = edge.copy(alpha = .55f)
                    listOf(Offset(inset, inset), Offset(size.width - inset, inset), Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset)).forEach { corner ->
                        val dx = if (corner.x > size.width / 2) -length else length
                        val dy = if (corner.y > size.height / 2) -length else length
                        drawLine(color, corner, corner + Offset(dx, 0f), 1.dp.toPx())
                        drawLine(color, corner, corner + Offset(0f, dy), 1.dp.toPx())
                    }
                }
            }
            if (kelli) Icon(KanvasBat, null, Modifier.align(Alignment.TopStart).padding(start = 30.dp, top = 28.dp).size(28.dp), tint = edge.copy(alpha = .85f))
        }
    }
}

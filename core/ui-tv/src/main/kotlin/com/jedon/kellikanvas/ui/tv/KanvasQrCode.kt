package com.jedon.kellikanvas.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasQrCode(value: String, description: String, modifier: Modifier = Modifier, size: Dp = 192.dp) {
    val edge = with(LocalDensity.current) { size.roundToPx() }
    val image = remember(value, edge) {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, edge, edge)
        val pixels = IntArray(edge * edge) { index -> if (matrix[index % edge, index / edge]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        createBitmap(edge, edge).apply { setPixels(pixels, 0, edge, 0, 0, edge, edge) }.asImageBitmap()
    }
    Image(image, description, modifier.size(size))
}

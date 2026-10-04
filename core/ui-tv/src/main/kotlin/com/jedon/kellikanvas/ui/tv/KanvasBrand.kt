package com.jedon.kellikanvas.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jedon.kellikanvas.model.AppTheme

/** The existing launcher monogram, displayed without recoloring the artwork. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasBrand(modifier: Modifier = Modifier, tagline: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.kanvas_logo), "KelliKanvas logo", Modifier.size(34.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("KelliKanvas", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (tagline) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (LocalKanvasTheme.current == AppTheme.KELLI) Icon(KanvasBat, null, Modifier.size(15.dp), tint = KanvasColors.Secondary)
                    Text("A personal gallery", style = MaterialTheme.typography.labelSmall, color = KanvasColors.Muted)
                }
            }
        }
    }
}

/** Small native vector accent; decorative and never drawn over a user's photograph. */
val KanvasBat: ImageVector by lazy {
    ImageVector.Builder("Bat", 24.dp, 18.dp, 24f, 18f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(0f, 4f)
            curveTo(4f, 6.5f, 6.5f, 8f, 9f, 7.5f)
            lineTo(10.5f, 3f)
            lineTo(12f, 5f)
            lineTo(13.5f, 3f)
            lineTo(15f, 7.5f)
            curveTo(17.5f, 8f, 20f, 6.5f, 24f, 4f)
            curveTo(24f, 8.5f, 22f, 12f, 19f, 14f)
            curveTo(18.2f, 11.5f, 16.8f, 11f, 15f, 12f)
            curveTo(14.5f, 14f, 13.5f, 15.5f, 12f, 17f)
            curveTo(10.5f, 15.5f, 9.5f, 14f, 9f, 12f)
            curveTo(7.2f, 11f, 5.8f, 11.5f, 5f, 14f)
            curveTo(2f, 12f, 0f, 8.5f, 0f, 4f)
            close()
        }
    }.build()
}

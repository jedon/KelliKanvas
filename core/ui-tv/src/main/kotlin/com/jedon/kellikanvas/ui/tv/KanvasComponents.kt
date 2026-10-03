package com.jedon.kellikanvas.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button as MaterialButton

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (focused || primary) KanvasColors.Accent else KanvasColors.Elevated,
        label = "buttonFocus",
    )
    MaterialButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, if (focused) KanvasColors.Text else Color.Transparent),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = if (focused || primary) KanvasColors.OnAccent else KanvasColors.Text,
            disabledContainerColor = KanvasColors.Elevated,
            disabledContentColor = KanvasColors.Muted,
        ),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    eyebrow: String = "KELLIKANVAS",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, color = KanvasColors.Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KanvasColors.Muted)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasNotice(
    message: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Surface(
        color = KanvasColors.Surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, KanvasColors.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            color = if (error) KanvasColors.Error else KanvasColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasNavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactions,
        color = when {
            focused -> KanvasColors.Accent
            selected -> KanvasColors.Elevated
            else -> Color.Transparent
        },
        contentColor = if (focused) {
            KanvasColors.OnAccent
        } else if (selected) {
            KanvasColors.Text
        } else {
            KanvasColors.Muted
        },
        shape = RoundedCornerShape(8.dp),
        border = if (focused) BorderStroke(2.dp, KanvasColors.Text) else null,
        modifier = modifier.fillMaxWidth().heightIn(min = 42.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun KanvasStatus(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(if (active) KanvasColors.Accent else KanvasColors.Muted, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = KanvasColors.Muted)
    }
}

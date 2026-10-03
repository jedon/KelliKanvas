package com.jedon.kellikanvas.feature.collection

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jedon.kellikanvas.ui.tv.KanvasButton

/** Consistent remote focus treatment for source actions. */
@Suppress("ktlint:standard:function-naming")
@Composable
fun HighContrastFocusButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeightDp: Int = 48,
) = KanvasButton(label, onClick, modifier.heightIn(min = minHeightDp.dp), enabled)

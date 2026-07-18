package com.jedon.kellikanvas.feature.settings

import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.platform.ambient.CapabilityStatus
import java.util.Locale
import kotlin.math.roundToInt

fun <T : Enum<T>> nextEnum(current: T): T {
    val values = current.javaClass.enumConstants
        ?: error("${current.javaClass.name} has no enum constants")
    return values[(current.ordinal + 1) % values.size]
}

fun coerceSlideDuration(millis: Long): Long = millis.coerceAtLeast(1_000)

fun coerceTransitionDuration(transitionMillis: Long, slideDurationMillis: Long): Long {
    val slide = coerceSlideDuration(slideDurationMillis)
    val maxTransition = (slide - 1).coerceAtLeast(0)
    return transitionMillis.coerceIn(0, maxTransition)
}

fun withSlideDuration(preferences: AppPreferences, slideDurationMillis: Long): AppPreferences {
    val slide = coerceSlideDuration(slideDurationMillis)
    return preferences.copy(
        slideDurationMillis = slide,
        transitionDurationMillis = coerceTransitionDuration(
            preferences.transitionDurationMillis,
            slide,
        ),
    )
}

fun withTransitionDuration(
    preferences: AppPreferences,
    transitionDurationMillis: Long,
): AppPreferences = preferences.copy(
    transitionDurationMillis = coerceTransitionDuration(
        transitionDurationMillis,
        preferences.slideDurationMillis,
    ),
)

fun clampBlurDim(amount: Double): Double = amount.coerceIn(0.0, 1.0)

fun clampPortraitLookAhead(value: Int): Int = value.coerceIn(1, 4)

fun clampPairGutter(value: Int): Int = value.coerceAtLeast(0)

fun formatDurationLabel(millis: Long): String = when {
    millis % 1_000L == 0L -> "${millis / 1_000L} s"
    millis % 100L == 0L && millis >= 1_000L ->
        String.format(Locale.US, "%.1f s", millis / 1_000.0)
    else -> "$millis ms"
}

fun formatBlurDimLabel(amount: Double): String = "${(clampBlurDim(amount) * 100.0).roundToInt()}%"

fun formatEnumLabel(value: Enum<*>): String = value.name
    .lowercase(Locale.US)
    .split('_')
    .joinToString(" ") { part ->
        part.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }
    }

fun isAmbientSensorModeEnabled(light: CapabilityStatus): Boolean = light == CapabilityStatus.AVAILABLE

fun isPresenceToggleEnabled(presence: CapabilityStatus): Boolean = presence != CapabilityStatus.UNAVAILABLE

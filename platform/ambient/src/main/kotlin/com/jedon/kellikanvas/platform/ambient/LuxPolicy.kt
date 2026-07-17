package com.jedon.kellikanvas.platform.ambient

import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow

data class LuxCalibration(
    val minLux: Float = 5f,
    val maxLux: Float = 500f,
    val minBrightness: Float = 0.08f,
    val maxBrightness: Float = 0.85f,
) {
    init {
        require(minLux.isFinite() && minLux > 0f)
        require(maxLux.isFinite() && maxLux > minLux)
        require(minBrightness.isFinite() && minBrightness in 0f..1f)
        require(maxBrightness.isFinite() && maxBrightness in 0f..1f)
        require(maxBrightness > minBrightness)
    }
}

enum class BrightnessMode {
    SENSOR,
    SCHEDULE,
    FOLLOW_TV,
}

class LuxPolicy(
    private val calibration: LuxCalibration = LuxCalibration(),
    private val halfLife: Duration = Duration.ofSeconds(5),
    private val minimumEmitInterval: Duration = Duration.ofSeconds(2),
    private val minimumBrightnessDelta: Float = 0.03f,
) {
    init {
        require(!halfLife.isZero && !halfLife.isNegative)
        require(!minimumEmitInterval.isNegative)
        require(minimumBrightnessDelta.isFinite() && minimumBrightnessDelta >= 0f)
    }

    var filteredLux: Float? = null
        private set

    private var lastSampleAt: Instant? = null
    private var lastEmittedAt: Instant? = null
    private var lastEmittedBrightness: Float? = null

    fun onLux(
        lux: Float,
        at: Instant,
        mode: BrightnessMode = BrightnessMode.SENSOR,
    ): Float? {
        if (!lux.isFinite() || lux < 0f || mode == BrightnessMode.FOLLOW_TV) return null
        val previousAt = lastSampleAt
        if (previousAt != null && at.isBefore(previousAt)) return null

        filteredLux =
            filteredLux?.let { previous ->
                val elapsedNanos = Duration.between(previousAt, at).toNanos().toDouble()
                val halfLifeNanos = halfLife.toNanos().toDouble()
                val alpha = 1.0 - 0.5.pow(elapsedNanos / halfLifeNanos)
                (previous + alpha * (lux - previous)).toFloat()
            } ?: lux
        lastSampleAt = at

        val brightness = mapLux(filteredLux!!)
        val elapsedEnough =
            lastEmittedAt?.let { !Duration.between(it, at).minus(minimumEmitInterval).isNegative }
                ?: true
        val changedEnough =
            lastEmittedBrightness?.let { kotlin.math.abs(brightness - it) >= minimumBrightnessDelta }
                ?: true
        if (!elapsedEnough || !changedEnough) return null

        lastEmittedAt = at
        lastEmittedBrightness = brightness
        return brightness
    }

    private fun mapLux(lux: Float): Float {
        val clamped = lux.coerceIn(calibration.minLux, calibration.maxLux)
        val fraction =
            ln(clamped / calibration.minLux) /
                ln(calibration.maxLux / calibration.minLux)
        return (
            calibration.minBrightness +
                fraction * (calibration.maxBrightness - calibration.minBrightness)
            ).coerceIn(calibration.minBrightness, calibration.maxBrightness)
    }
}

package com.jedon.kellikanvas.platform.ambient

sealed interface BrightnessDecision {
    data class Sensor(
        val brightness: Float,
    ) : BrightnessDecision {
        init {
            require(brightness.isFinite() && brightness in 0f..1f)
        }
    }

    data class Schedule(
        val brightness: Float,
    ) : BrightnessDecision {
        init {
            require(brightness.isFinite() && brightness in 0f..1f)
        }
    }

    data object FollowTv : BrightnessDecision
}

class AmbientCoordinator {
    fun resolve(
        sensorBrightness: Float?,
        scheduleBrightness: Float?,
        scheduleEnabled: Boolean = true,
    ): BrightnessDecision = when {
        sensorBrightness != null -> BrightnessDecision.Sensor(sensorBrightness.validBrightness())
        scheduleEnabled && scheduleBrightness != null ->
            BrightnessDecision.Schedule(scheduleBrightness.validBrightness())
        else -> BrightnessDecision.FollowTv
    }

    private fun Float.validBrightness(): Float {
        require(isFinite() && this in 0f..1f)
        return this
    }
}

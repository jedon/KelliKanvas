package com.jedon.kellikanvas.platform.ambient

sealed interface BrightnessDecision {
    data class Sensor(
        val brightness: Float,
    ) : BrightnessDecision

    data class Schedule(
        val brightness: Float,
    ) : BrightnessDecision

    data object FollowTv : BrightnessDecision
}

class AmbientCoordinator {
    fun resolve(
        sensorBrightness: Float?,
        scheduleBrightness: Float?,
    ): BrightnessDecision = when {
        sensorBrightness != null -> BrightnessDecision.Sensor(sensorBrightness.validBrightness())
        scheduleBrightness != null -> BrightnessDecision.Schedule(scheduleBrightness.validBrightness())
        else -> BrightnessDecision.FollowTv
    }

    private fun Float.validBrightness(): Float {
        require(isFinite() && this in 0f..1f)
        return this
    }
}

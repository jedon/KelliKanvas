package com.jedon.kellikanvas.platform.ambient

import java.time.Duration
import java.time.Instant

data class PresenceProfile(
    val samples: List<Float>,
) {
    init {
        require(samples.isNotEmpty())
        require(samples.all(Float::isFinite))
    }

    internal val minimum = samples.min()
    internal val maximum = samples.max()
}

data class Qualification(
    val sensorFingerprint: String,
    val buildFingerprint: String,
    val threshold: Float,
    val occupiedWhenAbove: Boolean,
) {
    fun isValidFor(
        sensor: SensorDescriptor,
        currentBuildFingerprint: String,
    ): Boolean = sensorFingerprint == sensor.fingerprint &&
        buildFingerprint == currentBuildFingerprint

    fun isOccupied(value: Float): Boolean = value.isFinite() &&
        if (occupiedWhenAbove) value >= threshold else value <= threshold

    companion object {
        fun qualify(
            sensor: SensorDescriptor,
            buildFingerprint: String,
            vacant: PresenceProfile,
            occupied: PresenceProfile,
        ): Qualification? {
            require(buildFingerprint.isNotBlank())
            val occupiedAbove = vacant.maximum < occupied.minimum
            val occupiedBelow = occupied.maximum < vacant.minimum
            if (!occupiedAbove && !occupiedBelow) return null

            val lowerMaximum = if (occupiedAbove) vacant.maximum else occupied.maximum
            val upperMinimum = if (occupiedAbove) occupied.minimum else vacant.minimum
            return Qualification(
                sensorFingerprint = sensor.fingerprint,
                buildFingerprint = buildFingerprint,
                threshold = lowerMaximum + (upperMinimum - lowerMaximum) / 2f,
                occupiedWhenAbove = occupiedAbove,
            )
        }
    }
}

enum class PlaybackState {
    PLAYING,
    PAUSED_BY_PRESENCE,
    PAUSED_MANUALLY,
    STOPPED,
}

enum class AmbientAction {
    NONE,
    PAUSE,
    RESUME,
}

class Gate private constructor(
    val qualification: Qualification,
    private val vacancyTimeout: Duration,
    private val resumeOnReturn: Boolean = true,
) {
    init {
        require(!vacancyTimeout.isNegative)
    }

    private var vacantSince: Instant? = null
    private var pauseIssued = false

    companion object {
        fun create(
            qualification: Qualification,
            sensor: SensorDescriptor,
            currentBuildFingerprint: String,
            vacancyTimeout: Duration,
            resumeOnReturn: Boolean = true,
        ): Gate? = if (qualification.isValidFor(sensor, currentBuildFingerprint)) {
            Gate(qualification, vacancyTimeout, resumeOnReturn)
        } else {
            null
        }
    }

    fun onSensorValue(
        value: Float,
        playbackState: PlaybackState,
        at: Instant,
    ): AmbientAction = onPresence(qualification.isOccupied(value), playbackState, at)

    fun onPresence(
        occupied: Boolean,
        playbackState: PlaybackState,
        at: Instant,
    ): AmbientAction {
        if (occupied) {
            vacantSince = null
            val shouldResume =
                pauseIssued &&
                    resumeOnReturn &&
                    playbackState == PlaybackState.PAUSED_BY_PRESENCE
            pauseIssued = false
            return if (shouldResume) AmbientAction.RESUME else AmbientAction.NONE
        }

        if (playbackState != PlaybackState.PLAYING) {
            vacantSince = null
            return AmbientAction.NONE
        }
        val started = vacantSince ?: at.also { vacantSince = it }
        if (Duration.between(started, at) < vacancyTimeout) return AmbientAction.NONE

        pauseIssued = true
        vacantSince = null
        return AmbientAction.PAUSE
    }
}

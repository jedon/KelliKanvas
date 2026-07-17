package com.jedon.kellikanvas.platform.ambient

import java.time.Duration

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
            inventory: Inventory,
            sensor: SensorDescriptor,
            buildFingerprint: String,
            vacant: PresenceProfile,
            occupied: PresenceProfile,
        ): Qualification? {
            require(buildFingerprint.isNotBlank())
            if (!inventory.containsPresenceCandidate(sensor)) return null
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

    private var vacantSinceNanos: Long? = null
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
        elapsedRealtimeNanos: Long,
    ): AmbientAction = onPresence(qualification.isOccupied(value), playbackState, elapsedRealtimeNanos)

    fun onPresence(
        occupied: Boolean,
        playbackState: PlaybackState,
        elapsedRealtimeNanos: Long,
    ): AmbientAction {
        require(elapsedRealtimeNanos >= 0L)
        if (occupied) {
            vacantSinceNanos = null
            val shouldResume =
                pauseIssued &&
                    resumeOnReturn &&
                    playbackState == PlaybackState.PAUSED_BY_PRESENCE
            if (playbackState != PlaybackState.PLAYING) pauseIssued = false
            return if (shouldResume) AmbientAction.RESUME else AmbientAction.NONE
        }

        if (pauseIssued) return AmbientAction.NONE
        if (playbackState != PlaybackState.PLAYING) {
            vacantSinceNanos = null
            return AmbientAction.NONE
        }
        val started = vacantSinceNanos ?: elapsedRealtimeNanos.also { vacantSinceNanos = it }
        val elapsed = (elapsedRealtimeNanos - started).coerceAtLeast(0L)
        if (elapsed < vacancyTimeout.toNanos()) return AmbientAction.NONE

        pauseIssued = true
        vacantSinceNanos = null
        return AmbientAction.PAUSE
    }
}

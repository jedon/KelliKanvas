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

    var vacancyDeadlineNanos: Long? = null
        private set
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
            vacancyDeadlineNanos = null
            val shouldResume =
                pauseIssued &&
                    resumeOnReturn &&
                    playbackState == PlaybackState.PAUSED_BY_PRESENCE
            pauseIssued = false
            return if (shouldResume) AmbientAction.RESUME else AmbientAction.NONE
        }

        if (pauseIssued) return AmbientAction.NONE
        if (playbackState != PlaybackState.PLAYING) {
            vacancyDeadlineNanos = null
            return AmbientAction.NONE
        }
        if (vacancyDeadlineNanos == null) {
            vacancyDeadlineNanos = saturatingAdd(elapsedRealtimeNanos, vacancyTimeout.toNanos())
        }
        return AmbientAction.NONE
    }

    fun onVacancyTimeout(
        playbackState: PlaybackState,
        elapsedRealtimeNanos: Long,
    ): AmbientAction {
        require(elapsedRealtimeNanos >= 0L)
        val deadline = vacancyDeadlineNanos ?: return AmbientAction.NONE
        if (elapsedRealtimeNanos < deadline) return AmbientAction.NONE
        vacancyDeadlineNanos = null
        if (pauseIssued || playbackState != PlaybackState.PLAYING) return AmbientAction.NONE

        pauseIssued = true
        return AmbientAction.PAUSE
    }

    private fun saturatingAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

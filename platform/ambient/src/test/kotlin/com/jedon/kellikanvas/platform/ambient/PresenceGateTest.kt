package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PresenceGateTest {
    private val sensor =
        SensorDescriptor(
            type = SensorTypes.PROXIMITY,
            name = "Room detector",
            vendor = "test",
            version = 1,
            resolution = 1f,
            maximumRange = 10f,
            powerMilliamp = 0f,
            minDelayMicros = 0,
            maxDelayMicros = 0,
            reportingMode = 0,
            isWakeUp = false,
            stringType = "test.presence",
        )
    private val now = Instant.parse("2026-07-17T12:00:00Z")

    @Test
    fun `guided vacant and occupied samples produce bound qualification`() {
        val qualification =
            Qualification.qualify(
                sensor = sensor,
                buildFingerprint = "firmware-a",
                vacant = PresenceProfile(listOf(0f, 0f, 1f)),
                occupied = PresenceProfile(listOf(8f, 9f, 10f)),
            )

        assertThat(qualification).isNotNull()
        assertThat(qualification!!.sensorFingerprint).isEqualTo(sensor.fingerprint)
        assertThat(qualification.buildFingerprint).isEqualTo("firmware-a")
    }

    @Test
    fun `overlapping guided samples do not qualify`() {
        assertThat(
            Qualification.qualify(
                sensor,
                "firmware-a",
                PresenceProfile(listOf(1f, 2f, 3f)),
                PresenceProfile(listOf(2f, 3f, 4f)),
            ),
        ).isNull()
    }

    @Test
    fun `firmware or sensor change invalidates qualification`() {
        val qualification = qualification()

        assertThat(qualification.isValidFor(sensor, "firmware-a")).isTrue()
        assertThat(qualification.isValidFor(sensor, "firmware-b")).isFalse()
        assertThat(qualification.isValidFor(sensor.copy(version = 2), "firmware-a")).isFalse()
        assertThat(
            Gate.create(
                qualification = qualification,
                sensor = sensor,
                currentBuildFingerprint = "firmware-b",
                vacancyTimeout = Duration.ZERO,
            ),
        ).isNull()
    }

    @Test
    fun `vacancy pauses only a playing slideshow after timeout`() {
        val gate = gate(vacancyTimeout = Duration.ofMinutes(2))

        assertThat(gate.onPresence(false, PlaybackState.PLAYING, now)).isEqualTo(AmbientAction.NONE)
        assertThat(
            gate.onPresence(false, PlaybackState.PLAYING, now.plusSeconds(119)),
        ).isEqualTo(AmbientAction.NONE)
        assertThat(
            gate.onPresence(false, PlaybackState.PLAYING, now.plusSeconds(120)),
        ).isEqualTo(AmbientAction.PAUSE)
    }

    @Test
    fun `presence resumes only a presence paused slideshow when enabled`() {
        val gate = gate(vacancyTimeout = Duration.ZERO, resumeOnReturn = true)
        assertThat(gate.onPresence(false, PlaybackState.PLAYING, now)).isEqualTo(AmbientAction.PAUSE)

        assertThat(
            gate.onPresence(true, PlaybackState.PAUSED_BY_PRESENCE, now.plusSeconds(1)),
        ).isEqualTo(AmbientAction.RESUME)
        assertThat(
            gate.onPresence(true, PlaybackState.PAUSED_MANUALLY, now.plusSeconds(2)),
        ).isEqualTo(AmbientAction.NONE)
    }

    @Test
    fun `disabled return option never resumes`() {
        val gate = gate(vacancyTimeout = Duration.ZERO, resumeOnReturn = false)
        gate.onPresence(false, PlaybackState.PLAYING, now)

        assertThat(
            gate.onPresence(true, PlaybackState.PAUSED_BY_PRESENCE, now.plusSeconds(1)),
        ).isEqualTo(AmbientAction.NONE)
    }

    @Test
    fun `ambient actions cannot express wake power or CEC commands`() {
        assertThat(AmbientAction.entries.map(AmbientAction::name))
            .containsExactly("NONE", "PAUSE", "RESUME")
            .inOrder()
    }

    private fun qualification() = Qualification.qualify(
        sensor,
        "firmware-a",
        PresenceProfile(listOf(0f, 0f, 1f)),
        PresenceProfile(listOf(8f, 9f, 10f)),
    )!!

    private fun gate(
        vacancyTimeout: Duration,
        resumeOnReturn: Boolean = true,
    ) = Gate.create(
        qualification = qualification(),
        sensor = sensor,
        currentBuildFingerprint = "firmware-a",
        vacancyTimeout = vacancyTimeout,
        resumeOnReturn = resumeOnReturn,
    )!!
}

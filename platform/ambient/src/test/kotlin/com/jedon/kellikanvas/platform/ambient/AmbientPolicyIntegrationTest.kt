package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class AmbientPolicyIntegrationTest {
    @Test
    fun `inventory retains required permission without recording sensor values`() {
        val sensor = descriptor(type = SensorTypes.LIGHT, requiredPermission = "permission.SENSOR")
        val diagnostic = Inventory(listOf(sensor)).diagnostics.single()

        assertThat(diagnostic.requiredPermission).isEqualTo("permission.SENSOR")
        assertThat(diagnostic.maximumRange).isEqualTo(10f)
        assertThat(diagnostic.resolution).isEqualTo(1f)
        assertThat(diagnostic.metadata).doesNotContain(sensor.fingerprint)
    }

    @Test
    fun `significant motion is diagnostic only and never presence eligible`() {
        val sensor = descriptor(type = SensorTypes.SIGNIFICANT_MOTION, name = "Significant motion")
        val inventory = Inventory(listOf(sensor))

        assertThat(inventory.presenceCandidates).isEmpty()
        assertThat(inventory.unsupportedPresenceSensors).containsExactly(sensor)
    }

    @Test
    fun `qualification rejects sensors outside inventory presence candidates`() {
        val motion = descriptor(type = SensorTypes.ACCELEROMETER, name = "Person motion")
        val inventory = Inventory(listOf(motion))

        val qualification =
            Qualification.qualify(
                inventory = inventory,
                sensor = motion,
                buildFingerprint = "firmware",
                vacant = PresenceProfile(listOf(0f, 1f)),
                occupied = PresenceProfile(listOf(8f, 9f)),
            )

        assertThat(qualification).isNull()
    }

    @Test
    fun `gate emits pause once while playback state propagation lags`() {
        val sensor = descriptor(type = SensorTypes.PROXIMITY)
        val inventory = Inventory(listOf(sensor))
        val qualification = qualification(inventory, sensor)
        val gate =
            Gate.create(
                qualification,
                sensor,
                "firmware",
                Duration.ZERO,
            )!!

        assertThat(gate.onPresence(false, PlaybackState.PLAYING, 1_000L))
            .isEqualTo(AmbientAction.NONE)
        assertThat(gate.onVacancyTimeout(PlaybackState.PLAYING, 1_000L))
            .isEqualTo(AmbientAction.PAUSE)
        assertThat(gate.onPresence(false, PlaybackState.PLAYING, 2_000L))
            .isEqualTo(AmbientAction.NONE)
        assertThat(gate.onPresence(false, PlaybackState.PAUSED_BY_PRESENCE, 3_000L))
            .isEqualTo(AmbientAction.NONE)
    }

    @Test
    fun `lux uses monotonic elapsed time and sensor mode only`() {
        val policy = LuxPolicy(minimumEmitInterval = Duration.ZERO, minimumBrightnessDelta = 0f)

        assertThat(policy.onLux(100f, 10_000_000_000L, BrightnessMode.SENSOR)).isNotNull()
        assertThat(policy.onLux(400f, 15_000_000_000L, BrightnessMode.SENSOR)).isNotNull()
        assertThat(policy.filteredLux).isWithin(0.01f).of(250f)
        assertThat(policy.onLux(500f, 14_000_000_000L, BrightnessMode.SENSOR)).isNull()
        assertThat(policy.filteredLux).isWithin(0.01f).of(250f)
        assertThat(policy.onLux(500f, 20_000_000_000L, BrightnessMode.SCHEDULE)).isNull()
        assertThat(policy.onLux(500f, 20_000_000_000L, BrightnessMode.FOLLOW_TV)).isNull()
    }

    @Test
    fun `existing schedule policy reads current timezone on every evaluation`() {
        var zone = ZoneId.of("America/Los_Angeles")
        val instant = Instant.parse("2026-03-08T11:30:00Z")
        val policy =
            SchedulePolicy(
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                zoneIdProvider = { zone },
            )

        assertThat(policy.brightness()).isEqualTo(0.15f)
        zone = ZoneId.of("America/New_York")
        assertThat(policy.brightness()).isEqualTo(0.70f)
    }

    @Test
    fun `ambient configuration defaults to no schedule or presence automation`() {
        val config = AmbientConfig()

        assertThat(config.scheduleEnabled).isFalse()
        assertThat(config.presenceEnabled).isFalse()
        assertThat(config.brightnessMode).isEqualTo(BrightnessMode.FOLLOW_TV)
        assertThat(config.luxCalibration).isEqualTo(LuxCalibration())
    }

    @Test
    fun `disabled schedule falls through to follow TV`() {
        val decision =
            AmbientCoordinator().resolve(
                sensorBrightness = null,
                scheduleBrightness = 0.7f,
                scheduleEnabled = false,
            )

        assertThat(decision).isEqualTo(BrightnessDecision.FollowTv)
    }

    private fun qualification(
        inventory: Inventory,
        sensor: SensorDescriptor,
    ) = Qualification.qualify(
        inventory,
        sensor,
        "firmware",
        PresenceProfile(listOf(0f, 1f)),
        PresenceProfile(listOf(8f, 9f)),
    )!!

    private fun descriptor(
        type: Int,
        name: String = "Sensor",
        requiredPermission: String? = null,
    ) = SensorDescriptor(
        type = type,
        name = name,
        vendor = "vendor",
        version = 1,
        resolution = 1f,
        maximumRange = 10f,
        powerMilliamp = 0f,
        minDelayMicros = 0,
        maxDelayMicros = 0,
        reportingMode = 0,
        isWakeUp = false,
        stringType = "test.$type",
        requiredPermission = requiredPermission,
    )
}

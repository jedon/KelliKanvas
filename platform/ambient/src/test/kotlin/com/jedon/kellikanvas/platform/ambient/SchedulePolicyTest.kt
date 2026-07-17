package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class SchedulePolicyTest {
    @Test
    fun `defaults to day at 0700 and night at 2100`() {
        assertThat(policyAt("2026-07-17T06:59:00Z").brightness()).isEqualTo(0.15f)
        assertThat(policyAt("2026-07-17T07:00:00Z").brightness()).isEqualTo(0.70f)
        assertThat(policyAt("2026-07-17T20:59:00Z").brightness()).isEqualTo(0.70f)
        assertThat(policyAt("2026-07-17T21:00:00Z").brightness()).isEqualTo(0.15f)
    }

    @Test
    fun `supports a day interval that crosses midnight`() {
        val schedule =
            DayNightSchedule(
                dayStarts = LocalTime.of(21, 0),
                nightStarts = LocalTime.of(7, 0),
            )

        assertThat(policyAt("2026-07-17T23:00:00Z", schedule).brightness()).isEqualTo(0.70f)
        assertThat(policyAt("2026-07-18T06:00:00Z", schedule).brightness()).isEqualTo(0.70f)
        assertThat(policyAt("2026-07-18T12:00:00Z", schedule).brightness()).isEqualTo(0.15f)
    }

    @Test
    fun `uses clocks current zone across DST and zone changes`() {
        val instant = Instant.parse("2026-03-08T11:30:00Z")
        val losAngeles = SchedulePolicy(clock = Clock.fixed(instant, ZoneId.of("America/Los_Angeles")))
        val newYork = SchedulePolicy(clock = Clock.fixed(instant, ZoneId.of("America/New_York")))

        assertThat(losAngeles.brightness()).isEqualTo(0.15f)
        assertThat(newYork.brightness()).isEqualTo(0.70f)
    }

    @Test
    fun `coordinator precedence is sensor then schedule then follow TV`() {
        val coordinator = AmbientCoordinator()

        assertThat(
            coordinator.resolve(sensorBrightness = 0.42f, scheduleBrightness = 0.70f),
        ).isEqualTo(BrightnessDecision.Sensor(0.42f))
        assertThat(
            coordinator.resolve(sensorBrightness = null, scheduleBrightness = 0.70f),
        ).isEqualTo(BrightnessDecision.Schedule(0.70f))
        assertThat(
            coordinator.resolve(sensorBrightness = null, scheduleBrightness = null),
        ).isEqualTo(BrightnessDecision.FollowTv)
    }

    private fun policyAt(
        instant: String,
        schedule: DayNightSchedule = DayNightSchedule(),
    ) = SchedulePolicy(
        schedule = schedule,
        clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")),
    )
}

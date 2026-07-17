package com.jedon.kellikanvas.platform.ambient

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class DayNightSchedule(
    val dayStarts: LocalTime = LocalTime.of(7, 0),
    val nightStarts: LocalTime = LocalTime.of(21, 0),
    val dayBrightness: Float = 0.70f,
    val nightBrightness: Float = 0.15f,
) {
    init {
        require(dayStarts != nightStarts)
        require(dayBrightness.isFinite() && dayBrightness in 0f..1f)
        require(nightBrightness.isFinite() && nightBrightness in 0f..1f)
    }
}

class SchedulePolicy(
    private val schedule: DayNightSchedule = DayNightSchedule(),
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun brightness(): Float {
        val localTime = clock.instant().atZone(zoneIdProvider()).toLocalTime()
        return if (isDay(localTime)) schedule.dayBrightness else schedule.nightBrightness
    }

    fun nextBoundaryInstant(): Instant {
        val now = clock.instant()
        val zone = zoneIdProvider()
        val localDate = now.atZone(zone).toLocalDate()
        return (0L..2L)
            .flatMap { dayOffset ->
                val date = localDate.plusDays(dayOffset)
                listOf(schedule.dayStarts, schedule.nightStarts)
                    .map { boundary -> date.atTime(boundary).atZone(zone).toInstant() }
            }
            .filter { it > now }
            .minOrNull()
            ?: error("A future schedule boundary must exist")
    }

    private fun isDay(time: LocalTime): Boolean = if (schedule.dayStarts < schedule.nightStarts) {
        time >= schedule.dayStarts && time < schedule.nightStarts
    } else {
        time >= schedule.dayStarts || time < schedule.nightStarts
    }
}

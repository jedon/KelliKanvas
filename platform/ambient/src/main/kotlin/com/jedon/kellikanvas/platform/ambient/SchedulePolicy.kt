package com.jedon.kellikanvas.platform.ambient

import java.time.Clock
import java.time.Duration
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
        val horizon = now.plus(Duration.ofDays(3))
        val candidates =
            (0L..2L)
                .flatMap { dayOffset ->
                    val date = localDate.plusDays(dayOffset)
                    listOf(schedule.dayStarts, schedule.nightStarts)
                        .flatMap { boundary ->
                            val localBoundary = date.atTime(boundary)
                            zone.rules.getValidOffsets(localBoundary)
                                .map(localBoundary::toInstant)
                        }
                }
                .toMutableSet()
        var transition = zone.rules.nextTransition(now)
        while (transition != null && transition.instant <= horizon) {
            candidates += transition.instant
            transition = zone.rules.nextTransition(transition.instant)
        }
        return candidates
            .asSequence()
            .filter { it > now }
            .sorted()
            .firstOrNull {
                brightnessAt(it.minusNanos(1), zone) != brightnessAt(it, zone)
            }
            ?: error("A future schedule boundary must exist")
    }

    private fun brightnessAt(
        instant: Instant,
        zone: ZoneId,
    ): Float {
        val localTime = instant.atZone(zone).toLocalTime()
        return if (isDay(localTime)) schedule.dayBrightness else schedule.nightBrightness
    }

    private fun isDay(time: LocalTime): Boolean = if (schedule.dayStarts < schedule.nightStarts) {
        time >= schedule.dayStarts && time < schedule.nightStarts
    } else {
        time >= schedule.dayStarts || time < schedule.nightStarts
    }
}

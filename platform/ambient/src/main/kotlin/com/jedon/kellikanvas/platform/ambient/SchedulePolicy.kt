package com.jedon.kellikanvas.platform.ambient

import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime

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
    private val clock: Clock,
) {
    constructor(
        schedule: DayNightSchedule = DayNightSchedule(),
    ) : this(schedule, Clock.systemDefaultZone())

    fun brightness(): Float {
        val localTime = ZonedDateTime.now(clock).toLocalTime()
        return if (isDay(localTime)) schedule.dayBrightness else schedule.nightBrightness
    }

    private fun isDay(time: LocalTime): Boolean = if (schedule.dayStarts < schedule.nightStarts) {
        time >= schedule.dayStarts && time < schedule.nightStarts
    } else {
        time >= schedule.dayStarts || time < schedule.nightStarts
    }
}

package com.jedon.kellikanvas.platform.ambient

import java.time.Duration

data class AmbientConfig(
    val brightnessMode: BrightnessMode = BrightnessMode.FOLLOW_TV,
    val scheduleEnabled: Boolean = false,
    val dayNightSchedule: DayNightSchedule = DayNightSchedule(),
    val luxCalibration: LuxCalibration = LuxCalibration(),
    val presenceEnabled: Boolean = false,
    val presenceQualification: Qualification? = null,
    val vacancyTimeout: Duration = Duration.ofMinutes(15),
    val resumeOnPresenceReturn: Boolean = false,
) {
    init {
        require(!vacancyTimeout.isNegative)
    }
}

fun interface AmbientConfigRepository {
    fun currentConfig(): AmbientConfig

    fun registerListener(onChanged: () -> Unit): AmbientRegistration = AmbientRegistration {}
}

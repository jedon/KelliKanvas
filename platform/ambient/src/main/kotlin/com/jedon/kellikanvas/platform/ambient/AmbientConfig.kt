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

    /**
     * Registers before reading the snapshot so an update cannot be lost between those operations.
     * Persistent adapters may override this to provide a native atomic subscribe-and-snapshot.
     */
    fun subscribe(onChanged: (AmbientConfig) -> Unit): AmbientConfigSubscription {
        val registration = registerListener { onChanged(currentConfig()) }
        return AmbientConfigSubscription(
            snapshot = currentConfig(),
            registration = registration,
        )
    }
}

data class AmbientConfigSubscription(
    val snapshot: AmbientConfig,
    val registration: AmbientRegistration,
)

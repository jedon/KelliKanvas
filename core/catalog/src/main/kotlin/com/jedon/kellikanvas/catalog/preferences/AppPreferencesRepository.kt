package com.jedon.kellikanvas.catalog.preferences

import com.jedon.kellikanvas.model.AppPreferences
import kotlinx.coroutines.flow.Flow

enum class HomeControl(
    val stableRoute: String,
) {
    START_OR_RESUME("home/start-or-resume"),
    COLLECTION("home/collection"),
    APPEARANCE("home/appearance"),
    PLAYBACK("home/playback"),
    AMBIENT_AND_SYSTEM("home/ambient-and-system"),
    ;

    companion object {
        fun fromStableRoute(route: String?): HomeControl = entries.firstOrNull { it.stableRoute == route } ?: START_OR_RESUME
    }
}

data class AppPreferencesState(
    val appPreferences: AppPreferences = AppPreferences(),
    val reducedMotion: Boolean = false,
    val lastHomeControl: HomeControl = HomeControl.START_OR_RESUME,
)

interface AppPreferencesRepository {
    val preferences: Flow<AppPreferencesState>

    suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState)

    suspend fun setSlideTiming(
        slideDurationMillis: Long,
        transitionDurationMillis: Long,
    )
}

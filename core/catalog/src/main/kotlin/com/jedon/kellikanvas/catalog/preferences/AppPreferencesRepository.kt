package com.jedon.kellikanvas.catalog.preferences

import com.jedon.kellikanvas.model.AppPreferences
import kotlinx.coroutines.flow.Flow

enum class HomeDestination(
    val stableRoute: String,
) {
    COLLECTION("collection"),
    SOURCES("sources"),
    SLIDESHOW("slideshow"),
    SETTINGS("settings"),
    ;

    companion object {
        fun fromStableRoute(route: String?): HomeDestination = entries.firstOrNull { it.stableRoute == route } ?: COLLECTION
    }
}

data class AppPreferencesState(
    val appPreferences: AppPreferences = AppPreferences(),
    val reducedMotion: Boolean = false,
    val lastHomeDestination: HomeDestination = HomeDestination.COLLECTION,
)

interface AppPreferencesRepository {
    val preferences: Flow<AppPreferencesState>

    suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState)

    suspend fun setSlideTiming(
        slideDurationMillis: Long,
        transitionDurationMillis: Long,
    )
}

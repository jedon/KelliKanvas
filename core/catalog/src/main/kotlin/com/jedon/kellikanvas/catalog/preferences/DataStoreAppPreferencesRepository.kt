package com.jedon.kellikanvas.catalog.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.jedon.kellikanvas.model.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override val preferences: Flow<AppPreferencesState> =
        dataStore.data.map(Preferences::toState)

    override suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState) {
        val updated = transform(preferences.first())
        dataStore.edit { mutablePreferences ->
            mutablePreferences.write(updated)
        }
    }

    override suspend fun setSlideTiming(
        slideDurationMillis: Long,
        transitionDurationMillis: Long,
    ) {
        update { current ->
            current.copy(
                appPreferences =
                current.appPreferences.copy(
                    slideDurationMillis = slideDurationMillis,
                    transitionDurationMillis = transitionDurationMillis,
                ),
            )
        }
    }
}

private fun Preferences.toState(): AppPreferencesState {
    val defaults = AppPreferences()
    val appPreferences =
        runCatching {
            AppPreferences(
                landscapeLayout = enumValueOrDefault(this[PreferenceKeys.landscapeLayout], defaults.landscapeLayout),
                singlePortraitLayout =
                enumValueOrDefault(
                    this[PreferenceKeys.singlePortraitLayout],
                    defaults.singlePortraitLayout,
                ),
                singlePortraitFit =
                enumValueOrDefault(
                    this[PreferenceKeys.singlePortraitFit],
                    defaults.singlePortraitFit,
                ),
                portraitPairingMode =
                enumValueOrDefault(
                    this[PreferenceKeys.portraitPairingMode],
                    defaults.portraitPairingMode,
                ),
                portraitLookAhead =
                this[PreferenceKeys.portraitLookAhead] ?: defaults.portraitLookAhead,
                pairGutterDp = this[PreferenceKeys.pairGutterDp] ?: defaults.pairGutterDp,
                blurStrength =
                enumValueOrDefault(this[PreferenceKeys.blurStrength], defaults.blurStrength),
                blurDimAmount =
                this[PreferenceKeys.blurDimAmount] ?: defaults.blurDimAmount,
                slideDurationMillis =
                this[PreferenceKeys.slideDurationMillis] ?: defaults.slideDurationMillis,
                transitionType =
                enumValueOrDefault(this[PreferenceKeys.transitionType], defaults.transitionType),
                transitionDurationMillis =
                this[PreferenceKeys.transitionDurationMillis]
                    ?: defaults.transitionDurationMillis,
                playbackOrder =
                enumValueOrDefault(this[PreferenceKeys.playbackOrder], defaults.playbackOrder),
                loopEnabled = this[PreferenceKeys.loopEnabled] ?: defaults.loopEnabled,
                resumeEnabled = this[PreferenceKeys.resumeEnabled] ?: defaults.resumeEnabled,
                newPhotosPolicy =
                enumValueOrDefault(
                    this[PreferenceKeys.newPhotosPolicy],
                    defaults.newPhotosPolicy,
                ),
                metadataOverlayEnabled =
                this[PreferenceKeys.metadataOverlayEnabled]
                    ?: defaults.metadataOverlayEnabled,
                clockOverlayEnabled =
                this[PreferenceKeys.clockOverlayEnabled] ?: defaults.clockOverlayEnabled,
                captureDateOverlayEnabled =
                this[PreferenceKeys.captureDateOverlayEnabled]
                    ?: defaults.captureDateOverlayEnabled,
                filenameOverlayEnabled =
                this[PreferenceKeys.filenameOverlayEnabled]
                    ?: defaults.filenameOverlayEnabled,
                presenceEnabled =
                this[PreferenceKeys.presenceEnabled] ?: defaults.presenceEnabled,
                brightnessMode =
                enumValueOrDefault(this[PreferenceKeys.brightnessMode], defaults.brightnessMode),
            )
        }.getOrDefault(defaults)
    return AppPreferencesState(
        appPreferences = appPreferences,
        reducedMotion = this[PreferenceKeys.reducedMotion] ?: false,
        lastHomeDestination =
        HomeDestination.fromStableRoute(this[PreferenceKeys.lastHomeDestination]),
    )
}

private fun MutablePreferences.write(state: AppPreferencesState) {
    val values = state.appPreferences
    this[PreferenceKeys.landscapeLayout] = values.landscapeLayout.name
    this[PreferenceKeys.singlePortraitLayout] = values.singlePortraitLayout.name
    this[PreferenceKeys.singlePortraitFit] = values.singlePortraitFit.name
    this[PreferenceKeys.portraitPairingMode] = values.portraitPairingMode.name
    this[PreferenceKeys.portraitLookAhead] = values.portraitLookAhead
    this[PreferenceKeys.pairGutterDp] = values.pairGutterDp
    this[PreferenceKeys.blurStrength] = values.blurStrength.name
    this[PreferenceKeys.blurDimAmount] = values.blurDimAmount
    this[PreferenceKeys.slideDurationMillis] = values.slideDurationMillis
    this[PreferenceKeys.transitionType] = values.transitionType.name
    this[PreferenceKeys.transitionDurationMillis] = values.transitionDurationMillis
    this[PreferenceKeys.playbackOrder] = values.playbackOrder.name
    this[PreferenceKeys.loopEnabled] = values.loopEnabled
    this[PreferenceKeys.resumeEnabled] = values.resumeEnabled
    this[PreferenceKeys.newPhotosPolicy] = values.newPhotosPolicy.name
    this[PreferenceKeys.metadataOverlayEnabled] = values.metadataOverlayEnabled
    this[PreferenceKeys.clockOverlayEnabled] = values.clockOverlayEnabled
    this[PreferenceKeys.captureDateOverlayEnabled] = values.captureDateOverlayEnabled
    this[PreferenceKeys.filenameOverlayEnabled] = values.filenameOverlayEnabled
    this[PreferenceKeys.presenceEnabled] = values.presenceEnabled
    this[PreferenceKeys.brightnessMode] = values.brightnessMode.name
    this[PreferenceKeys.reducedMotion] = state.reducedMotion
    this[PreferenceKeys.lastHomeDestination] = state.lastHomeDestination.stableRoute
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String?,
    default: T,
): T = enumValues<T>().firstOrNull { it.name == value } ?: default

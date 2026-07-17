package com.jedon.kellikanvas.catalog.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.jedon.kellikanvas.model.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

class DataStoreAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override val preferences: Flow<AppPreferencesState> =
        dataStore.data
            .catch { failure ->
                if (failure is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw failure
                }
            }.map(Preferences::toState)

    override suspend fun update(transform: (AppPreferencesState) -> AppPreferencesState) {
        dataStore.edit { current ->
            current.write(transform(current.toState()))
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

object DataStoreAppPreferencesRepositoryFactory {
    fun create(
        file: File,
        scope: CoroutineScope,
    ): DataStoreAppPreferencesRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
        return DataStoreAppPreferencesRepository(dataStore)
    }
}

private fun Preferences.toState(): AppPreferencesState {
    val defaults = AppPreferences()
    val slideDurationMillis =
        validOrDefault(
            this[PreferenceKeys.slideDurationMillis],
            defaults.slideDurationMillis,
        ) { it >= 1_000 }
    val transitionDurationMillis =
        validOrDefault(
            this[PreferenceKeys.transitionDurationMillis],
            defaults.transitionDurationMillis.coerceAtMost(slideDurationMillis - 1),
        ) { it >= 0 && it < slideDurationMillis }
    return AppPreferencesState(
        appPreferences =
        AppPreferences(
            landscapeLayout =
            PreferenceEnumCodes.layoutMode.decode(
                this[PreferenceKeys.landscapeLayout],
                defaults.landscapeLayout,
            ),
            singlePortraitLayout =
            PreferenceEnumCodes.layoutMode.decode(
                this[PreferenceKeys.singlePortraitLayout],
                defaults.singlePortraitLayout,
            ),
            singlePortraitFit =
            PreferenceEnumCodes.portraitFit.decode(
                this[PreferenceKeys.singlePortraitFit],
                defaults.singlePortraitFit,
            ),
            portraitPairingMode =
            PreferenceEnumCodes.portraitPairingMode.decode(
                this[PreferenceKeys.portraitPairingMode],
                defaults.portraitPairingMode,
            ),
            portraitLookAhead =
            validOrDefault(
                this[PreferenceKeys.portraitLookAhead],
                defaults.portraitLookAhead,
            ) { it in 1..4 },
            pairGutterDp =
            validOrDefault(
                this[PreferenceKeys.pairGutterDp],
                defaults.pairGutterDp,
            ) { it >= 0 },
            blurStrength =
            PreferenceEnumCodes.blurStrength.decode(
                this[PreferenceKeys.blurStrength],
                defaults.blurStrength,
            ),
            blurDimAmount =
            validOrDefault(
                this[PreferenceKeys.blurDimAmount],
                defaults.blurDimAmount,
            ) { it in 0.0..1.0 },
            slideDurationMillis = slideDurationMillis,
            transitionType =
            PreferenceEnumCodes.transitionType.decode(
                this[PreferenceKeys.transitionType],
                defaults.transitionType,
            ),
            transitionDurationMillis = transitionDurationMillis,
            playbackOrder =
            PreferenceEnumCodes.playbackOrder.decode(
                this[PreferenceKeys.playbackOrder],
                defaults.playbackOrder,
            ),
            loopEnabled = this[PreferenceKeys.loopEnabled] ?: defaults.loopEnabled,
            resumeEnabled = this[PreferenceKeys.resumeEnabled] ?: defaults.resumeEnabled,
            newPhotosPolicy =
            PreferenceEnumCodes.newPhotosPolicy.decode(
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
            PreferenceEnumCodes.brightnessMode.decode(
                this[PreferenceKeys.brightnessMode],
                defaults.brightnessMode,
            ),
        ),
        reducedMotion = this[PreferenceKeys.reducedMotion] ?: false,
        lastHomeControl =
        HomeControl.fromStableRoute(this[PreferenceKeys.lastHomeControl]),
    )
}

private fun MutablePreferences.write(state: AppPreferencesState) {
    val values = state.appPreferences
    clear()
    this[PreferenceKeys.landscapeLayout] =
        PreferenceEnumCodes.layoutMode.encode(values.landscapeLayout)
    this[PreferenceKeys.singlePortraitLayout] =
        PreferenceEnumCodes.layoutMode.encode(values.singlePortraitLayout)
    this[PreferenceKeys.singlePortraitFit] =
        PreferenceEnumCodes.portraitFit.encode(values.singlePortraitFit)
    this[PreferenceKeys.portraitPairingMode] =
        PreferenceEnumCodes.portraitPairingMode.encode(values.portraitPairingMode)
    this[PreferenceKeys.portraitLookAhead] = values.portraitLookAhead
    this[PreferenceKeys.pairGutterDp] = values.pairGutterDp
    this[PreferenceKeys.blurStrength] =
        PreferenceEnumCodes.blurStrength.encode(values.blurStrength)
    this[PreferenceKeys.blurDimAmount] = values.blurDimAmount
    this[PreferenceKeys.slideDurationMillis] = values.slideDurationMillis
    this[PreferenceKeys.transitionType] =
        PreferenceEnumCodes.transitionType.encode(values.transitionType)
    this[PreferenceKeys.transitionDurationMillis] = values.transitionDurationMillis
    this[PreferenceKeys.playbackOrder] =
        PreferenceEnumCodes.playbackOrder.encode(values.playbackOrder)
    this[PreferenceKeys.loopEnabled] = values.loopEnabled
    this[PreferenceKeys.resumeEnabled] = values.resumeEnabled
    this[PreferenceKeys.newPhotosPolicy] =
        PreferenceEnumCodes.newPhotosPolicy.encode(values.newPhotosPolicy)
    this[PreferenceKeys.metadataOverlayEnabled] = values.metadataOverlayEnabled
    this[PreferenceKeys.clockOverlayEnabled] = values.clockOverlayEnabled
    this[PreferenceKeys.captureDateOverlayEnabled] = values.captureDateOverlayEnabled
    this[PreferenceKeys.filenameOverlayEnabled] = values.filenameOverlayEnabled
    this[PreferenceKeys.presenceEnabled] = values.presenceEnabled
    this[PreferenceKeys.brightnessMode] =
        PreferenceEnumCodes.brightnessMode.encode(values.brightnessMode)
    this[PreferenceKeys.reducedMotion] = state.reducedMotion
    this[PreferenceKeys.lastHomeControl] = state.lastHomeControl.stableRoute
}

private inline fun <T> validOrDefault(
    value: T?,
    default: T,
    isValid: (T) -> Boolean,
): T = value?.takeIf(isValid) ?: default

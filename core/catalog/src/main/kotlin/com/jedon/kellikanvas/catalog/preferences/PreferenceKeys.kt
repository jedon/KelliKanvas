package com.jedon.kellikanvas.catalog.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferenceKeys {
    val landscapeLayout = stringPreferencesKey("landscape_layout_v1")
    val singlePortraitLayout = stringPreferencesKey("single_portrait_layout_v1")
    val singlePortraitFit = stringPreferencesKey("single_portrait_fit_v1")
    val portraitPairingMode = stringPreferencesKey("portrait_pairing_mode_v1")
    val portraitLookAhead = intPreferencesKey("portrait_look_ahead_v1")
    val pairGutterDp = intPreferencesKey("pair_gutter_dp_v1")
    val blurStrength = stringPreferencesKey("blur_strength_v1")
    val blurDimAmount = doublePreferencesKey("blur_dim_amount_v1")
    val slideDurationMillis = longPreferencesKey("slide_duration_millis_v1")
    val transitionType = stringPreferencesKey("transition_type_v1")
    val transitionDurationMillis = longPreferencesKey("transition_duration_millis_v1")
    val playbackOrder = stringPreferencesKey("playback_order_v1")
    val loopEnabled = booleanPreferencesKey("loop_enabled_v1")
    val resumeEnabled = booleanPreferencesKey("resume_enabled_v1")
    val newPhotosPolicy = stringPreferencesKey("new_photos_policy_v1")
    val metadataOverlayEnabled = booleanPreferencesKey("metadata_overlay_enabled_v1")
    val clockOverlayEnabled = booleanPreferencesKey("clock_overlay_enabled_v1")
    val captureDateOverlayEnabled = booleanPreferencesKey("capture_date_overlay_enabled_v1")
    val filenameOverlayEnabled = booleanPreferencesKey("filename_overlay_enabled_v1")
    val presenceEnabled = booleanPreferencesKey("presence_enabled_v1")
    val brightnessMode = stringPreferencesKey("brightness_mode_v1")
    val reducedMotion = booleanPreferencesKey("reduced_motion_v1")
    val lastHomeControl = stringPreferencesKey("last_home_control_v1")

    val keys: Set<Preferences.Key<*>> =
        setOf(
            landscapeLayout,
            singlePortraitLayout,
            singlePortraitFit,
            portraitPairingMode,
            portraitLookAhead,
            pairGutterDp,
            blurStrength,
            blurDimAmount,
            slideDurationMillis,
            transitionType,
            transitionDurationMillis,
            playbackOrder,
            loopEnabled,
            resumeEnabled,
            newPhotosPolicy,
            metadataOverlayEnabled,
            clockOverlayEnabled,
            captureDateOverlayEnabled,
            filenameOverlayEnabled,
            presenceEnabled,
            brightnessMode,
            reducedMotion,
            lastHomeControl,
        )
    val names: Set<String> = keys.mapTo(linkedSetOf()) { it.name }

    init {
        val forbiddenTerms = listOf("password", "token", "secret", "credential")
        require(names.none { name -> forbiddenTerms.any(name.lowercase()::contains) }) {
            "Preference key names must never describe credentials"
        }
    }
}

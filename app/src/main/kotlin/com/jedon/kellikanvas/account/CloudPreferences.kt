package com.jedon.kellikanvas.account

import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.model.AppPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal fun AppPreferencesState.cloudJson(): JsonObject = buildJsonObject {
    val p = appPreferences
    put("theme", p.theme.name)
    put("landscapeLayout", p.landscapeLayout.name)
    put("singlePortraitLayout", p.singlePortraitLayout.name)
    put("singlePortraitFit", p.singlePortraitFit.name)
    put("portraitPairingMode", p.portraitPairingMode.name)
    put("portraitLookAhead", p.portraitLookAhead)
    put("pairGutterDp", p.pairGutterDp)
    put("blurStrength", p.blurStrength.name)
    put("blurDimAmount", p.blurDimAmount)
    put("slideDurationMillis", p.slideDurationMillis)
    put("transitionType", p.transitionType.name)
    put("transitionDurationMillis", p.transitionDurationMillis)
    put("playbackOrder", p.playbackOrder.name)
    put("loopEnabled", p.loopEnabled)
    put("resumeEnabled", p.resumeEnabled)
    put("newPhotosPolicy", p.newPhotosPolicy.name)
    put("metadataOverlayEnabled", p.metadataOverlayEnabled)
    put("clockOverlayEnabled", p.clockOverlayEnabled)
    put("captureDateOverlayEnabled", p.captureDateOverlayEnabled)
    put("filenameOverlayEnabled", p.filenameOverlayEnabled)
    put("presenceEnabled", p.presenceEnabled)
    put("brightnessMode", p.brightnessMode.name)
    put("reducedMotion", reducedMotion)
}

internal fun JsonObject.cloudPreferences(): AppPreferencesState {
    val p = AppPreferences()
    fun bool(key: String, default: Boolean) = this[key]?.jsonPrimitive?.boolean ?: default
    fun long(key: String, default: Long) = this[key]?.jsonPrimitive?.long ?: default
    return AppPreferencesState(
        appPreferences = p.copy(
            theme = choice("theme", p.theme), landscapeLayout = choice("landscapeLayout", p.landscapeLayout),
            singlePortraitLayout = choice("singlePortraitLayout", p.singlePortraitLayout), singlePortraitFit = choice("singlePortraitFit", p.singlePortraitFit),
            portraitPairingMode = choice("portraitPairingMode", p.portraitPairingMode), portraitLookAhead = long("portraitLookAhead", p.portraitLookAhead.toLong()).toInt(),
            pairGutterDp = long("pairGutterDp", p.pairGutterDp.toLong()).toInt(), blurStrength = choice("blurStrength", p.blurStrength),
            blurDimAmount = this["blurDimAmount"]?.jsonPrimitive?.double ?: p.blurDimAmount,
            slideDurationMillis = long("slideDurationMillis", p.slideDurationMillis), transitionType = choice("transitionType", p.transitionType),
            transitionDurationMillis = long("transitionDurationMillis", p.transitionDurationMillis), playbackOrder = choice("playbackOrder", p.playbackOrder),
            loopEnabled = bool("loopEnabled", p.loopEnabled), resumeEnabled = bool("resumeEnabled", p.resumeEnabled), newPhotosPolicy = choice("newPhotosPolicy", p.newPhotosPolicy),
            metadataOverlayEnabled = bool("metadataOverlayEnabled", p.metadataOverlayEnabled), clockOverlayEnabled = bool("clockOverlayEnabled", p.clockOverlayEnabled),
            captureDateOverlayEnabled = bool("captureDateOverlayEnabled", p.captureDateOverlayEnabled), filenameOverlayEnabled = bool("filenameOverlayEnabled", p.filenameOverlayEnabled),
            presenceEnabled = bool("presenceEnabled", p.presenceEnabled), brightnessMode = choice("brightnessMode", p.brightnessMode),
        ),
        reducedMotion = bool("reducedMotion", false),
    )
}
private inline fun <reified T : Enum<T>> JsonObject.choice(key: String, default: T): T = this[key]?.jsonPrimitive?.content?.let { enumValueOf<T>(it) } ?: default

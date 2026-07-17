package com.jedon.kellikanvas.catalog.preferences

import com.jedon.kellikanvas.model.BlurStrength
import com.jedon.kellikanvas.model.BrightnessMode
import com.jedon.kellikanvas.model.LayoutMode
import com.jedon.kellikanvas.model.NewPhotosPolicy
import com.jedon.kellikanvas.model.PlaybackOrder
import com.jedon.kellikanvas.model.PortraitFit
import com.jedon.kellikanvas.model.PortraitPairingMode
import com.jedon.kellikanvas.model.TransitionType

internal object PreferenceEnumCodes {
    val layoutMode =
        StableEnumCodec(
            LayoutMode.FULL_PHOTO to "layout.full_photo.v1",
            LayoutMode.FILL_SCREEN to "layout.fill_screen.v1",
            LayoutMode.BLURRED_BORDER to "layout.blurred_border.v1",
            LayoutMode.SOLID_BACKGROUND to "layout.solid_background.v1",
            LayoutMode.STRETCH to "layout.stretch.v1",
        )
    val portraitFit =
        StableEnumCodec(
            PortraitFit.FULL_HEIGHT to "portrait_fit.full_height.v1",
            PortraitFit.FILL_SCREEN to "portrait_fit.fill_screen.v1",
        )
    val portraitPairingMode =
        StableEnumCodec(
            PortraitPairingMode.OFF to "portrait_pairing.off.v1",
            PortraitPairingMode.AUTO to "portrait_pairing.auto.v1",
            PortraitPairingMode.ALWAYS to "portrait_pairing.always.v1",
        )
    val blurStrength =
        StableEnumCodec(
            BlurStrength.LOW to "blur.low.v1",
            BlurStrength.MEDIUM to "blur.medium.v1",
            BlurStrength.HIGH to "blur.high.v1",
        )
    val transitionType =
        StableEnumCodec(
            TransitionType.CUT to "transition.cut.v1",
            TransitionType.CROSSFADE to "transition.crossfade.v1",
            TransitionType.FADE_THROUGH_BLACK to "transition.fade_through_black.v1",
            TransitionType.SLIDE_LEFT to "transition.slide_left.v1",
            TransitionType.SLIDE_RIGHT to "transition.slide_right.v1",
            TransitionType.PAN_ZOOM to "transition.pan_zoom.v1",
            TransitionType.RANDOM to "transition.random.v1",
        )
    val playbackOrder =
        StableEnumCodec(
            PlaybackOrder.SHUFFLE to "playback.shuffle.v1",
            PlaybackOrder.NAME to "playback.name.v1",
            PlaybackOrder.CAPTURE_DATE_ASC to "playback.capture_date_asc.v1",
            PlaybackOrder.CAPTURE_DATE_DESC to "playback.capture_date_desc.v1",
            PlaybackOrder.MODIFIED_DATE_ASC to "playback.modified_date_asc.v1",
            PlaybackOrder.MODIFIED_DATE_DESC to "playback.modified_date_desc.v1",
        )
    val newPhotosPolicy =
        StableEnumCodec(
            NewPhotosPolicy.NEXT_CYCLE to "new_photos.next_cycle.v1",
        )
    val brightnessMode =
        StableEnumCodec(
            BrightnessMode.FOLLOW_TV to "brightness.follow_tv.v1",
            BrightnessMode.AMBIENT_SENSOR to "brightness.ambient_sensor.v1",
            BrightnessMode.SCHEDULE to "brightness.schedule.v1",
        )
}

internal class StableEnumCodec<T : Enum<T>>(
    vararg entries: Pair<T, String>,
) {
    private val codeByValue = entries.toMap()
    private val valueByCode = entries.associate { (value, code) -> code to value }

    init {
        require(codeByValue.size == entries.size) { "Stable enum values must be unique" }
        require(valueByCode.size == entries.size) { "Stable enum codes must be unique" }
    }

    fun encode(value: T): String = checkNotNull(codeByValue[value]) {
        "Missing stable preference code for $value"
    }

    fun decode(
        code: String?,
        fallback: T,
    ): T = valueByCode[code] ?: fallback
}

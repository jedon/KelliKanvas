package com.jedon.kellikanvas.feature.settings

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.BlurStrength
import com.jedon.kellikanvas.model.BrightnessMode
import com.jedon.kellikanvas.model.LayoutMode
import com.jedon.kellikanvas.model.PlaybackOrder
import com.jedon.kellikanvas.model.TransitionType
import com.jedon.kellikanvas.platform.ambient.CapabilityStatus
import org.junit.Test

class SettingsHelpersTest {
    @Test
    fun nextEnum_cyclesThroughEntries() {
        assertThat(nextEnum(LayoutMode.FULL_PHOTO)).isEqualTo(LayoutMode.FILL_SCREEN)
        assertThat(nextEnum(LayoutMode.STRETCH)).isEqualTo(LayoutMode.FULL_PHOTO)
        assertThat(nextEnum(BlurStrength.HIGH)).isEqualTo(BlurStrength.LOW)
        assertThat(nextEnum(PlaybackOrder.MODIFIED_DATE_DESC)).isEqualTo(PlaybackOrder.SHUFFLE)
    }

    @Test
    fun coerceSlideDuration_enforcesMinimumOneSecond() {
        assertThat(coerceSlideDuration(999)).isEqualTo(1_000)
        assertThat(coerceSlideDuration(1_000)).isEqualTo(1_000)
        assertThat(coerceSlideDuration(15_000)).isEqualTo(15_000)
    }

    @Test
    fun coerceTransitionDuration_staysNonNegativeAndBelowSlide() {
        assertThat(coerceTransitionDuration(-1, 5_000)).isEqualTo(0)
        assertThat(coerceTransitionDuration(700, 5_000)).isEqualTo(700)
        assertThat(coerceTransitionDuration(5_000, 5_000)).isEqualTo(4_999)
        assertThat(coerceTransitionDuration(700, 1_000)).isEqualTo(700)
    }

    @Test
    fun withSlideDuration_coercesTransitionWhenNeeded() {
        val prefs = AppPreferences(slideDurationMillis = 15_000, transitionDurationMillis = 700)
        val shortened = withSlideDuration(prefs, 1_000)
        assertThat(shortened.slideDurationMillis).isEqualTo(1_000)
        assertThat(shortened.transitionDurationMillis).isEqualTo(700)

        val tooShort = withSlideDuration(
            AppPreferences(slideDurationMillis = 15_000, transitionDurationMillis = 2_000),
            1_500,
        )
        assertThat(tooShort.slideDurationMillis).isEqualTo(1_500)
        assertThat(tooShort.transitionDurationMillis).isEqualTo(1_499)
    }

    @Test
    fun clampBlurDim_staysInUnitInterval() {
        assertThat(clampBlurDim(-0.1)).isEqualTo(0.0)
        assertThat(clampBlurDim(0.35)).isEqualTo(0.35)
        assertThat(clampBlurDim(1.2)).isEqualTo(1.0)
    }

    @Test
    fun clampPortraitLookAhead_staysBetweenOneAndFour() {
        assertThat(clampPortraitLookAhead(0)).isEqualTo(1)
        assertThat(clampPortraitLookAhead(3)).isEqualTo(3)
        assertThat(clampPortraitLookAhead(9)).isEqualTo(4)
    }

    @Test
    fun clampPairGutter_isNonNegative() {
        assertThat(clampPairGutter(-4)).isEqualTo(0)
        assertThat(clampPairGutter(24)).isEqualTo(24)
    }

    @Test
    fun formatDurationLabel_usesSecondsOrMilliseconds() {
        assertThat(formatDurationLabel(15_000)).isEqualTo("15 s")
        assertThat(formatDurationLabel(1_000)).isEqualTo("1 s")
        assertThat(formatDurationLabel(700)).isEqualTo("700 ms")
        assertThat(formatDurationLabel(1_500)).isEqualTo("1.5 s")
    }

    @Test
    fun formatBlurDimLabel_showsPercent() {
        assertThat(formatBlurDimLabel(0.35)).isEqualTo("35%")
        assertThat(formatBlurDimLabel(0.0)).isEqualTo("0%")
        assertThat(formatBlurDimLabel(1.0)).isEqualTo("100%")
    }

    @Test
    fun formatEnumLabel_titleCasesUnderscores() {
        assertThat(formatEnumLabel(LayoutMode.BLURRED_BORDER)).isEqualTo("Blurred Border")
        assertThat(formatEnumLabel(TransitionType.FADE_THROUGH_BLACK)).isEqualTo("Fade Through Black")
        assertThat(formatEnumLabel(BrightnessMode.FOLLOW_TV)).isEqualTo("Follow Tv")
    }

    @Test
    fun ambientSensorModeEnabled_onlyWhenLightAvailable() {
        assertThat(isAmbientSensorModeEnabled(CapabilityStatus.AVAILABLE)).isTrue()
        assertThat(isAmbientSensorModeEnabled(CapabilityStatus.UNAVAILABLE)).isFalse()
        assertThat(isAmbientSensorModeEnabled(CapabilityStatus.CANDIDATE_UNVERIFIED)).isFalse()
    }

    @Test
    fun presenceToggleEnabled_whenNotUnavailable() {
        assertThat(isPresenceToggleEnabled(CapabilityStatus.UNAVAILABLE)).isFalse()
        assertThat(isPresenceToggleEnabled(CapabilityStatus.CANDIDATE_UNVERIFIED)).isTrue()
        assertThat(isPresenceToggleEnabled(CapabilityStatus.AVAILABLE)).isTrue()
    }

    @Test
    fun nextAllowedBrightnessMode_skipsAmbientSensorWhenUnavailable() {
        assertThat(
            nextAllowedBrightnessMode(
                current = BrightnessMode.FOLLOW_TV,
                lightAvailable = false,
            ),
        ).isEqualTo(BrightnessMode.SCHEDULE)
        assertThat(
            nextAllowedBrightnessMode(
                current = BrightnessMode.SCHEDULE,
                lightAvailable = false,
            ),
        ).isEqualTo(BrightnessMode.FOLLOW_TV)
        assertThat(
            nextAllowedBrightnessMode(
                current = BrightnessMode.FOLLOW_TV,
                lightAvailable = true,
            ),
        ).isEqualTo(BrightnessMode.AMBIENT_SENSOR)
    }
}

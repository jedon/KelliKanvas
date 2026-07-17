package com.jedon.kellikanvas.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun `defaults match every approved playback and appearance decision`() {
        val preferences = AppPreferences()

        assertThat(preferences.landscapeLayout).isEqualTo(LayoutMode.FILL_SCREEN)
        assertThat(preferences.singlePortraitLayout).isEqualTo(LayoutMode.BLURRED_BORDER)
        assertThat(preferences.singlePortraitFit).isEqualTo(PortraitFit.FULL_HEIGHT)
        assertThat(preferences.portraitPairingMode).isEqualTo(PortraitPairingMode.AUTO)
        assertThat(preferences.portraitLookAhead).isEqualTo(4)
        assertThat(preferences.pairGutterDp).isEqualTo(24)
        assertThat(preferences.blurStrength).isEqualTo(BlurStrength.MEDIUM)
        assertThat(preferences.blurDimAmount).isEqualTo(0.35)
        assertThat(preferences.slideDurationMillis).isEqualTo(15_000)
        assertThat(preferences.transitionType).isEqualTo(TransitionType.CROSSFADE)
        assertThat(preferences.transitionDurationMillis).isEqualTo(700)
        assertThat(preferences.playbackOrder).isEqualTo(PlaybackOrder.SHUFFLE)
        assertThat(preferences.loopEnabled).isTrue()
        assertThat(preferences.resumeEnabled).isTrue()
        assertThat(preferences.newPhotosPolicy).isEqualTo(NewPhotosPolicy.NEXT_CYCLE)
        assertThat(preferences.metadataOverlayEnabled).isFalse()
        assertThat(preferences.clockOverlayEnabled).isFalse()
        assertThat(preferences.captureDateOverlayEnabled).isFalse()
        assertThat(preferences.filenameOverlayEnabled).isFalse()
        assertThat(preferences.presenceEnabled).isFalse()
        assertThat(preferences.brightnessMode).isEqualTo(BrightnessMode.FOLLOW_TV)
    }

    @Test
    fun `slide duration is at least one second`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(slideDurationMillis = 999)
        }
    }

    @Test
    fun `transition duration is nonnegative and shorter than slide duration`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(transitionDurationMillis = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(slideDurationMillis = 1_000, transitionDurationMillis = 1_000)
        }
    }

    @Test
    fun `portrait look ahead is between one and four`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(portraitLookAhead = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(portraitLookAhead = 5)
        }
    }

    @Test
    fun `pair gutter is nonnegative`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(pairGutterDp = -1)
        }
    }

    @Test
    fun `blur dim amount is between zero and one`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(blurDimAmount = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferences(blurDimAmount = 1.01)
        }
    }

    @Test
    fun `valid boundary values are accepted`() {
        val minimumSlide =
            AppPreferences(
                slideDurationMillis = 1_000,
                transitionDurationMillis = 999,
            )

        assertThat(minimumSlide.slideDurationMillis).isEqualTo(1_000)
        assertThat(minimumSlide.transitionDurationMillis).isEqualTo(999)
        assertThat(AppPreferences(transitionDurationMillis = 0).transitionDurationMillis)
            .isEqualTo(0)
        assertThat(AppPreferences(blurDimAmount = 0.0).blurDimAmount).isEqualTo(0.0)
        assertThat(AppPreferences(blurDimAmount = 1.0).blurDimAmount).isEqualTo(1.0)
        assertThat(AppPreferences(portraitLookAhead = 1).portraitLookAhead).isEqualTo(1)
        assertThat(AppPreferences(portraitLookAhead = 4).portraitLookAhead).isEqualTo(4)
        assertThat(AppPreferences(pairGutterDp = 0).pairGutterDp).isEqualTo(0)
    }
}

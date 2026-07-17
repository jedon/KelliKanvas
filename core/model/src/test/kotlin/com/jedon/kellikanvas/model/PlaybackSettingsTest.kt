package com.jedon.kellikanvas.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSettingsTest {
    @Test
    fun `layout modes match the approved contract`() {
        assertThat(LayoutMode.entries)
            .containsExactly(
                LayoutMode.FULL_PHOTO,
                LayoutMode.FILL_SCREEN,
                LayoutMode.BLURRED_BORDER,
                LayoutMode.SOLID_BACKGROUND,
                LayoutMode.STRETCH,
            ).inOrder()
    }

    @Test
    fun `portrait pairing modes match the approved contract`() {
        assertThat(PortraitPairingMode.entries)
            .containsExactly(
                PortraitPairingMode.OFF,
                PortraitPairingMode.AUTO,
                PortraitPairingMode.ALWAYS,
            ).inOrder()
    }

    @Test
    fun `transition types match the approved contract`() {
        assertThat(TransitionType.entries)
            .containsExactly(
                TransitionType.CUT,
                TransitionType.CROSSFADE,
                TransitionType.FADE_THROUGH_BLACK,
                TransitionType.SLIDE_LEFT,
                TransitionType.SLIDE_RIGHT,
                TransitionType.PAN_ZOOM,
                TransitionType.RANDOM,
            ).inOrder()
    }

    @Test
    fun `playback orders match the approved contract`() {
        assertThat(PlaybackOrder.entries)
            .containsExactly(
                PlaybackOrder.SHUFFLE,
                PlaybackOrder.NAME,
                PlaybackOrder.CAPTURE_DATE_ASC,
                PlaybackOrder.CAPTURE_DATE_DESC,
                PlaybackOrder.MODIFIED_DATE_ASC,
                PlaybackOrder.MODIFIED_DATE_DESC,
            ).inOrder()
    }
}

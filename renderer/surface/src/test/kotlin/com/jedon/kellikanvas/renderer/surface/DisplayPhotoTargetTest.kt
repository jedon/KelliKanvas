package com.jedon.kellikanvas.renderer.surface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayPhotoTargetTest {
    @Test
    fun prefersExactUhdModeWhenAvailable() {
        val current = DisplaySize(1920, 1080)
        val selected =
            DisplayPhotoTarget.preferMode(
                available =
                listOf(
                    DisplaySize(1920, 1080),
                    DisplaySize(3840, 2160),
                    DisplaySize(1280, 720),
                ),
                current = current,
            )
        assertThat(selected).isEqualTo(DisplaySize(3840, 2160))
    }

    @Test
    fun fallsBackToLargestModeWhenUhdMissing() {
        val selected =
            DisplayPhotoTarget.preferMode(
                available =
                listOf(
                    DisplaySize(1280, 720),
                    DisplaySize(2560, 1440),
                    DisplaySize(1920, 1080),
                ),
                current = DisplaySize(1920, 1080),
            )
        assertThat(selected).isEqualTo(DisplaySize(2560, 1440))
    }

    @Test
    fun televisionDecodeTargetUsesPanelLongEdge() {
        assertThat(
            DisplayPhotoTarget.decodeLongEdgePx(DisplaySize(3840, 2160), television = true),
        ).isEqualTo(3840)
        assertThat(
            DisplayPhotoTarget.decodeLongEdgePx(DisplaySize(1920, 1080), television = true),
        ).isEqualTo(1920)
    }

    @Test
    fun phoneDecodeTargetCapsBelowArbitrary4k() {
        assertThat(
            DisplayPhotoTarget.decodeLongEdgePx(DisplaySize(3840, 2160), television = false),
        ).isEqualTo(DisplayPhotoTarget.NON_TV_MAX_EDGE_PX)
        assertThat(
            DisplayPhotoTarget.decodeLongEdgePx(DisplaySize(1080, 2400), television = false),
        ).isEqualTo(DisplayPhotoTarget.NON_TV_MAX_EDGE_PX)
        assertThat(
            DisplayPhotoTarget.decodeLongEdgePx(DisplaySize(1080, 1920), television = false),
        ).isEqualTo(1920)
    }
}

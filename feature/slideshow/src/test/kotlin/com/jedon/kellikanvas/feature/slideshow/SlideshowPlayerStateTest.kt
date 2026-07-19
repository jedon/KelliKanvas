package com.jedon.kellikanvas.feature.slideshow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlideshowPlayerStateTest {
    @Test
    fun nextWrapsAndPauseStopsAdvanceFlag() {
        val state = SlideshowPlayerState(total = 3, intervalMillis = 15_000)

        state.next()
        assertThat(state.index).isEqualTo(1)
        state.next()
        state.next()
        assertThat(state.index).isEqualTo(0)

        state.pause()
        assertThat(state.playing).isFalse()
        state.prev()
        assertThat(state.index).isEqualTo(2)
    }
}

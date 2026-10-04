package com.jedon.kellikanvas.feature.slideshow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlideshowPlayerStateTest {
    @Test
    fun cloudRefreshPreservesPauseAndWrapsWithinTheNewPlaylist() {
        val state = SlideshowPlayerState(3, 15_000)
        state.pause()
        state.updatePlaylistSize(2, 1)
        assertThat(state.playing).isFalse()
        assertThat(state.index).isEqualTo(1)
        state.next()
        assertThat(state.index).isEqualTo(0)
        state.prev()
        assertThat(state.index).isEqualTo(1)
        state.updatePlaylistSize(1, 10)
        assertThat(state.index).isEqualTo(0)
    }

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

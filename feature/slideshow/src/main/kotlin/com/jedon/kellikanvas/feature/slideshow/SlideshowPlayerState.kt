package com.jedon.kellikanvas.feature.slideshow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SlideshowPlayerState(
    total: Int,
    val intervalMillis: Long,
) {
    private val totalItems = total.also { require(it > 0) { "Slideshow must contain at least one item" } }

    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(true)
        private set

    fun next() {
        index = (index + 1) % totalItems
    }

    fun prev() {
        index = (index - 1 + totalItems) % totalItems
    }

    fun pause() {
        playing = false
    }

    fun resume() {
        playing = true
    }

    fun togglePause() {
        playing = !playing
    }
}

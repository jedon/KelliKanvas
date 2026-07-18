package com.jedon.kellikanvas.home

/**
 * One-shot gate for post-bootstrap slideshow auto-start.
 *
 * [token] must be cleared when consumed so recomposing Home after Back does not
 * re-trigger navigation.
 */
object AutoStartSlideshowToken {
    fun consumeIfReady(
        token: Int,
        canStart: Boolean,
        onConsumed: () -> Unit,
    ): Boolean {
        if (token <= 0 || !canStart) return false
        onConsumed()
        return true
    }
}

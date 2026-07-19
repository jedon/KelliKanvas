package com.jedon.kellikanvas.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoStartSlideshowTokenTest {
    @Test
    fun consumesPositiveTokenWhenReady() {
        var token = 1
        val fired =
            AutoStartSlideshowToken.consumeIfReady(
                token = token,
                canStart = true,
                onConsumed = { token = 0 },
            )
        assertThat(fired).isTrue()
        assertThat(token).isEqualTo(0)
    }

    @Test
    fun ignoresZeroToken() {
        var consumed = false
        val fired =
            AutoStartSlideshowToken.consumeIfReady(
                token = 0,
                canStart = true,
                onConsumed = { consumed = true },
            )
        assertThat(fired).isFalse()
        assertThat(consumed).isFalse()
    }

    @Test
    fun ignoresWhenCannotStart() {
        var token = 2
        val fired =
            AutoStartSlideshowToken.consumeIfReady(
                token = token,
                canStart = false,
                onConsumed = { token = 0 },
            )
        assertThat(fired).isFalse()
        assertThat(token).isEqualTo(2)
    }

    @Test
    fun secondCallAfterConsumeDoesNotFire() {
        var token = 1
        AutoStartSlideshowToken.consumeIfReady(token, true) { token = 0 }
        val again =
            AutoStartSlideshowToken.consumeIfReady(token, true) { token = -1 }
        assertThat(again).isFalse()
        assertThat(token).isEqualTo(0)
    }
}

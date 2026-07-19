package com.jedon.kellikanvas.feature.slideshow

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException

class BriefErrorReasonTest {
    @Test
    fun prefersMessage() {
        assertThat(briefErrorReason(IOException("Failed to decode bitmap")))
            .isEqualTo("Failed to decode bitmap")
    }

    @Test
    fun fallsBackToClassName() {
        assertThat(briefErrorReason(OutOfMemoryError()))
            .isEqualTo("OutOfMemoryError")
    }

    @Test
    fun truncatesLongMessages() {
        val long = "x".repeat(200)
        assertThat(briefErrorReason(IOException(long)).length).isEqualTo(96)
    }
}

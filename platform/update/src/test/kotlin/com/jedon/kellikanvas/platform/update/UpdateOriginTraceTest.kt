package com.jedon.kellikanvas.platform.update

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.net.URI

class UpdateOriginTraceTest {
    @After
    fun tearDown() {
        UpdateOriginTrace.clear()
    }

    @Test
    fun `starts empty`() {
        UpdateOriginTrace.clear()
        assertThat(UpdateOriginTrace.last()).isNull()
    }

    @Test
    fun `record keeps the most recent origin`() {
        UpdateOriginTrace.record(URI("http://darklingnas:8088/update-envelope.json"), 1_000L)
        UpdateOriginTrace.record(URI("http://192.168.68.81:8088/update-envelope.json"), 2_000L)

        assertThat(UpdateOriginTrace.last()).isEqualTo(
            UpdateOriginUsed(URI("http://192.168.68.81:8088/update-envelope.json"), 2_000L),
        )
    }

    @Test
    fun `clear removes the recorded origin`() {
        UpdateOriginTrace.record(URI("http://darklingnas:8088/update-envelope.json"), 1_000L)
        UpdateOriginTrace.clear()

        assertThat(UpdateOriginTrace.last()).isNull()
    }
}

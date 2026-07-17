package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

class LuxPolicyTest {
    private val start = Instant.parse("2026-07-17T12:00:00Z")

    @Test
    fun `rejects non finite and negative readings`() {
        val policy = LuxPolicy()

        assertThat(policy.onLux(Float.NaN, start)).isNull()
        assertThat(policy.onLux(Float.POSITIVE_INFINITY, start)).isNull()
        assertThat(policy.onLux(-1f, start)).isNull()
    }

    @Test
    fun `uses time aware five second half life EMA`() {
        val policy = LuxPolicy(minimumEmitInterval = Duration.ZERO, minimumBrightnessDelta = 0f)
        policy.onLux(100f, start)

        policy.onLux(400f, start.plusSeconds(5))

        assertThat(policy.filteredLux).isWithin(0.01f).of(250f)
        policy.onLux(400f, start.plusSeconds(10))
        assertThat(policy.filteredLux).isWithin(0.01f).of(325f)
    }

    @Test
    fun `maps logarithmically and clamps to calibrated window range`() {
        fun mapped(lux: Float) = LuxPolicy(
            minimumEmitInterval = Duration.ZERO,
            minimumBrightnessDelta = 0f,
        )
            .onLux(lux, start)

        assertThat(mapped(0f)).isWithin(0.0001f).of(0.08f)
        assertThat(mapped(500f)).isWithin(0.0001f).of(0.85f)
        assertThat(mapped(5_000f)).isWithin(0.0001f).of(0.85f)
        assertThat(mapped(sqrt(5f * 500f)))
            .isWithin(0.01f)
            .of((0.08f + 0.85f) / 2f)
    }

    @Test
    fun `waits at least two seconds between emissions`() {
        val policy = LuxPolicy()
        assertThat(policy.onLux(5f, start)).isEqualTo(0.08f)

        assertThat(policy.onLux(500f, start.plusSeconds(1))).isNull()
        assertThat(policy.onLux(500f, start.plusSeconds(2))).isNotNull()
    }

    @Test
    fun `suppresses brightness changes below point zero three`() {
        val policy = LuxPolicy()
        assertThat(policy.onLux(100f, start)).isNotNull()

        assertThat(policy.onLux(101f, start.plusSeconds(2))).isNull()
    }

    @Test
    fun `follow TV disables lux output`() {
        val policy = LuxPolicy()

        assertThat(policy.onLux(100f, start, mode = BrightnessMode.FOLLOW_TV)).isNull()
    }

    @Test
    fun `calibration validates all bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            LuxCalibration(minLux = 500f, maxLux = 5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LuxCalibration(minBrightness = 0.9f, maxBrightness = 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LuxCalibration(minBrightness = -0.1f)
        }
    }
}

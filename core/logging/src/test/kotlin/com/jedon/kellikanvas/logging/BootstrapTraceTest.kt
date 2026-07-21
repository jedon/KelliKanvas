package com.jedon.kellikanvas.logging

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class BootstrapTraceTest {
    @After
    fun tearDown() {
        BootstrapTrace.clear()
    }

    @Test
    fun recordThenLastReturnsRecord() {
        val record =
            BootstrapTraceRecord(
                startedAtMillis = 42L,
                steps =
                listOf(
                    BootstrapTraceStep("SMB connect", ok = true),
                    BootstrapTraceStep("SMB root listing", ok = false, detail = "no roots"),
                ),
                result = "Failed: no roots",
            )

        BootstrapTrace.record(record)

        assertThat(BootstrapTrace.last()).isEqualTo(record)
    }

    @Test
    fun recordReplacesPreviousRecord() {
        BootstrapTrace.record(BootstrapTraceRecord(1L, emptyList(), "first"))
        BootstrapTrace.record(BootstrapTraceRecord(2L, emptyList(), "second"))

        assertThat(BootstrapTrace.last()?.result).isEqualTo("second")
    }

    @Test
    fun clearRemovesRecord() {
        BootstrapTrace.record(BootstrapTraceRecord(1L, emptyList(), "only"))

        BootstrapTrace.clear()

        assertThat(BootstrapTrace.last()).isNull()
    }
}

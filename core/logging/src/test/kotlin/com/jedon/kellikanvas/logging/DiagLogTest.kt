package com.jedon.kellikanvas.logging

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class DiagLogTest {
    @Before
    fun setUp() {
        DiagLog.installSink(null)
        DiagLog.clear()
    }

    @After
    fun tearDown() {
        DiagLog.installSink(null)
        DiagLog.clear()
    }

    @Test
    fun `snapshot returns entries oldest first`() {
        DiagLog.d("tag", "first")
        DiagLog.i("tag", "second")
        DiagLog.w("tag", "third")

        val snapshot = DiagLog.snapshot()

        assertThat(snapshot.map(DiagLogEntry::message))
            .containsExactly("first", "second", "third")
            .inOrder()
        assertThat(snapshot.map(DiagLogEntry::level))
            .containsExactly(DiagLogLevel.DEBUG, DiagLogLevel.INFO, DiagLogLevel.WARN)
            .inOrder()
    }

    @Test
    fun `buffer evicts oldest entries beyond capacity`() {
        repeat(DiagLog.CAPACITY + 25) { index ->
            DiagLog.i("tag", "message $index")
        }

        val snapshot = DiagLog.snapshot()

        assertThat(snapshot).hasSize(DiagLog.CAPACITY)
        assertThat(snapshot.first().message).isEqualTo("message 25")
        assertThat(snapshot.last().message).isEqualTo("message ${DiagLog.CAPACITY + 24}")
    }

    @Test
    fun `clear removes all entries`() {
        DiagLog.e("tag", "boom")

        DiagLog.clear()

        assertThat(DiagLog.snapshot()).isEmpty()
    }

    @Test
    fun `installed sink receives every appended entry`() {
        val received = mutableListOf<DiagLogEntry>()
        DiagLog.installSink { entry, _ -> received.add(entry) }

        DiagLog.w("tag", "watch out")

        assertThat(received).hasSize(1)
        assertThat(received.single().level).isEqualTo(DiagLogLevel.WARN)
        assertThat(received.single().tag).isEqualTo("tag")
        assertThat(received.single().message).isEqualTo("watch out")
    }

    @Test
    fun `sink receives the original throwable`() {
        val received = mutableListOf<Throwable?>()
        DiagLog.installSink { _, throwable -> received.add(throwable) }
        val failure = IllegalStateException("boom")

        DiagLog.e("tag", "with throwable", failure)
        DiagLog.i("tag", "without throwable")

        assertThat(received).containsExactly(failure, null).inOrder()
    }

    @Test
    fun `removed sink no longer receives entries`() {
        val received = mutableListOf<DiagLogEntry>()
        DiagLog.installSink { entry, _ -> received.add(entry) }
        DiagLog.installSink(null)

        DiagLog.i("tag", "quiet")

        assertThat(received).isEmpty()
    }

    @Test
    fun `sink exception does not propagate and entry is still buffered`() {
        DiagLog.installSink { _, _ -> throw IllegalStateException("sink broke") }

        DiagLog.e("tag", "still recorded")

        assertThat(DiagLog.snapshot().single().message).isEqualTo("still recorded")
    }

    @Test
    fun `throwable summary is simple name and message`() {
        DiagLog.e("tag", "failed", IllegalArgumentException("bad input"))

        val entry = DiagLog.snapshot().single()

        assertThat(entry.throwableSummary).isEqualTo("IllegalArgumentException: bad input")
    }

    @Test
    fun `throwable summary falls back for anonymous exception classes`() {
        val anonymous = object : RuntimeException("anon boom") {}

        DiagLog.e("tag", "failed", anonymous)

        val summary = DiagLog.snapshot().single().throwableSummary
        assertThat(summary).endsWith(": anon boom")
        assertThat(summary).isNotEqualTo(": anon boom")
    }

    @Test
    fun `throwable summary is null when no throwable given`() {
        DiagLog.i("tag", "plain")

        assertThat(DiagLog.snapshot().single().throwableSummary).isNull()
    }

    @Test
    fun `each level helper records the matching level`() {
        DiagLog.d("tag", "d")
        DiagLog.i("tag", "i")
        DiagLog.w("tag", "w")
        DiagLog.e("tag", "e")

        assertThat(DiagLog.snapshot().map(DiagLogEntry::level))
            .containsExactly(
                DiagLogLevel.DEBUG,
                DiagLogLevel.INFO,
                DiagLogLevel.WARN,
                DiagLogLevel.ERROR,
            )
            .inOrder()
    }
}

package com.jedon.kellikanvas.diagnostics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityTestRunnerTest {
    @Test
    fun `runs checks in order and publishes each result`() = runTest {
        val runner = ConnectivityTestRunner(
            buildChecks = {
                listOf(
                    ConnectivityCheck("first") { ConnectivityCheckOutcome(ok = true, detail = "one") },
                    ConnectivityCheck("second") { ConnectivityCheckOutcome(ok = false, detail = "two") },
                )
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        runner.run()

        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.results).containsExactly(
            ConnectivityCheckResult("first", ok = true, detail = "one"),
            ConnectivityCheckResult("second", ok = false, detail = "two"),
        ).inOrder()
    }

    @Test
    fun `a throwing check fails inline and later checks still run`() = runTest {
        val runner = ConnectivityTestRunner(
            buildChecks = {
                listOf(
                    ConnectivityCheck("boom") { throw IOException("connection refused") },
                    ConnectivityCheck("after") { ConnectivityCheckOutcome(ok = true, detail = "ok") },
                )
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        runner.run()

        assertThat(runner.state.value.results).containsExactly(
            ConnectivityCheckResult("boom", ok = false, detail = "IOException: connection refused"),
            ConnectivityCheckResult("after", ok = true, detail = "ok"),
        ).inOrder()
    }

    @Test
    fun `a failing check build reports a single failed result`() = runTest {
        val runner = ConnectivityTestRunner(
            buildChecks = { throw IllegalStateException("no adapters") },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        runner.run()

        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.results).containsExactly(
            ConnectivityCheckResult("Prepare checks", ok = false, detail = "IllegalStateException: no adapters"),
        )
    }

    @Test
    fun `a second run while running is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = ConnectivityTestRunner(
            buildChecks = {
                listOf(
                    ConnectivityCheck("gated") {
                        gate.await()
                        ConnectivityCheckOutcome(ok = true, detail = "done")
                    },
                )
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val job = launch { runner.run() }
        runCurrent()
        assertThat(runner.state.value.running).isTrue()

        runner.run()
        assertThat(runner.state.value.results).isEmpty()

        gate.complete(Unit)
        job.join()
        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.results).containsExactly(
            ConnectivityCheckResult("gated", ok = true, detail = "done"),
        )
    }

    @Test
    fun `a new run replaces previous results`() = runTest {
        val runner = ConnectivityTestRunner(
            buildChecks = {
                listOf(ConnectivityCheck("only") { ConnectivityCheckOutcome(ok = true, detail = "ok") })
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        runner.run()
        runner.run()

        assertThat(runner.state.value.results).hasSize(1)
    }
}

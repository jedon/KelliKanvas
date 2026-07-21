package com.jedon.kellikanvas.diagnostics

import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.diagnosticSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One named connectivity check to run on demand. */
data class ConnectivityCheck(
    val name: String,
    val run: suspend () -> ConnectivityCheckOutcome,
)

data class ConnectivityCheckOutcome(
    val ok: Boolean,
    val detail: String,
)

data class ConnectivityCheckResult(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

data class ConnectivityTestState(
    val running: Boolean = false,
    val results: List<ConnectivityCheckResult> = emptyList(),
)

/**
 * Runs the Diagnostics connectivity checks sequentially on [dispatcher], publishing
 * each result as it lands so the screen can render progress inline. Re-entrant
 * calls while a run is in flight are ignored (the button is disabled anyway).
 *
 * [buildChecks] is invoked per run because the check list depends on runtime state
 * (resolved NAS host, currently restored adapters).
 */
class ConnectivityTestRunner(
    private val buildChecks: suspend () -> List<ConnectivityCheck>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(ConnectivityTestState())
    val state: StateFlow<ConnectivityTestState> = _state.asStateFlow()

    suspend fun run() {
        val previous = _state.getAndUpdate { current ->
            if (current.running) current else ConnectivityTestState(running = true)
        }
        if (previous.running) return
        DiagLog.i(TAG, "Connectivity test started")
        try {
            withContext(dispatcher) {
                val checks =
                    try {
                        buildChecks()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        publish(
                            ConnectivityCheckResult(
                                name = "Prepare checks",
                                ok = false,
                                detail = failure.diagnosticSummary(),
                            ),
                        )
                        emptyList()
                    }
                for (check in checks) {
                    val result =
                        try {
                            val outcome = check.run()
                            ConnectivityCheckResult(check.name, outcome.ok, outcome.detail)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            ConnectivityCheckResult(check.name, ok = false, detail = failure.diagnosticSummary())
                        }
                    publish(result)
                }
            }
        } finally {
            _state.update { it.copy(running = false) }
        }
        DiagLog.i(TAG, "Connectivity test finished")
    }

    private fun publish(result: ConnectivityCheckResult) {
        if (result.ok) {
            DiagLog.i(TAG, "Check passed — ${result.name}: ${result.detail}")
        } else {
            DiagLog.w(TAG, "Check failed — ${result.name}: ${result.detail}")
        }
        _state.update { it.copy(results = it.results + result) }
    }

    private companion object {
        const val TAG = "ConnectivityTest"
    }
}

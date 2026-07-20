package com.jedon.kellikanvas

import android.app.Application
import android.util.Log
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.DiagLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KelliKanvasApp : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installLogcatMirror()
        container = AppContainer(this)
        // Once per process start; the 24h UpdateCheckPolicy gating applies (manual=false).
        // A null controller (no pinned metadata key in this build) silently no-ops.
        container.updateCheckController?.let { controller ->
            applicationScope.launch { controller.checkForUpdatesOnStartup() }
        }
    }

    private fun installLogcatMirror() {
        DiagLog.installSink { entry, throwable ->
            when (entry.level) {
                DiagLogLevel.DEBUG -> Log.d(entry.tag, entry.message, throwable)
                DiagLogLevel.INFO -> Log.i(entry.tag, entry.message, throwable)
                DiagLogLevel.WARN -> Log.w(entry.tag, entry.message, throwable)
                DiagLogLevel.ERROR -> Log.e(entry.tag, entry.message, throwable)
            }
        }
    }
}

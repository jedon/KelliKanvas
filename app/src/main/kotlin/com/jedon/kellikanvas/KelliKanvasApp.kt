package com.jedon.kellikanvas

import android.app.Application
import android.util.Log
import com.jedon.kellikanvas.logging.DiagLog
import com.jedon.kellikanvas.logging.DiagLogLevel

class KelliKanvasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installLogcatMirror()
        container = AppContainer(this)
    }

    private fun installLogcatMirror() {
        DiagLog.installSink { entry ->
            val message =
                entry.throwableSummary
                    ?.let { "${entry.message}: $it" }
                    ?: entry.message
            when (entry.level) {
                DiagLogLevel.DEBUG -> Log.d(entry.tag, message)
                DiagLogLevel.INFO -> Log.i(entry.tag, message)
                DiagLogLevel.WARN -> Log.w(entry.tag, message)
                DiagLogLevel.ERROR -> Log.e(entry.tag, message)
            }
        }
    }
}

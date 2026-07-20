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

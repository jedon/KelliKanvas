package com.jedon.kellikanvas

import android.app.Application

class KelliKanvasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

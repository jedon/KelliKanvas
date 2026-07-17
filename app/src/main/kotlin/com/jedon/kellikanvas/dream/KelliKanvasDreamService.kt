@file:Suppress("DEPRECATION")

package com.jedon.kellikanvas.dream

import android.app.Application
import android.service.dreams.DreamService
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

interface DreamSlideshowHost {
    fun hasPlayableCollection(): Boolean

    fun attach(container: ViewGroup)

    fun detach()

    data object Unavailable : DreamSlideshowHost {
        override fun hasPlayableCollection() = false

        override fun attach(container: ViewGroup) = Unit

        override fun detach() = Unit
    }
}

interface DreamSlideshowHostProvider {
    fun dreamSlideshowHost(): DreamSlideshowHost?
}

internal fun resolveDreamSlideshowHost(application: Application): DreamSlideshowHost = (
    application as? DreamSlideshowHostProvider
    )?.dreamSlideshowHost()
    ?: DreamSlideshowHost.Unavailable

class DreamSession(
    private val host: DreamSlideshowHost,
) {
    private var attached = false

    fun start(
        container: ViewGroup,
        finish: () -> Unit,
    ): Boolean {
        if (!host.hasPlayableCollection()) {
            finish()
            return false
        }
        host.attach(container)
        attached = true
        return true
    }

    fun stop() {
        if (!attached) return
        host.detach()
        attached = false
    }
}

class DreamLifecycle(
    host: DreamSlideshowHost,
    private val containerProvider: () -> ViewGroup?,
    private val finish: () -> Unit,
) {
    private val session = DreamSession(host)
    private var dreaming = false

    fun onDreamingStarted() {
        if (dreaming) return
        dreaming = true
        val container = containerProvider()
        if (container == null) {
            finish()
            return
        }
        session.start(container, finish)
    }

    fun onDreamingStopped() {
        if (!dreaming) return
        dreaming = false
        session.stop()
    }
}

open class KelliKanvasDreamService : DreamService() {
    private var lifecycle: DreamLifecycle? = null

    protected open fun createSlideshowHost(): DreamSlideshowHost = resolveDreamSlideshowHost(application)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        configureDreamWindow(window)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycle =
            DreamLifecycle(
                host = createSlideshowHost(),
                containerProvider = { window?.decorView as? ViewGroup },
                finish = ::finish,
            ).also(DreamLifecycle::onDreamingStarted)
    }

    override fun onDreamingStopped() {
        lifecycle?.onDreamingStopped()
        lifecycle = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycle?.onDreamingStopped()
        lifecycle = null
        super.onDetachedFromWindow()
    }
}

internal fun configureDreamWindow(window: Window?) {
    window ?: return
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    window.clearFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
    )
}

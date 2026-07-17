@file:Suppress("DEPRECATION")

package com.jedon.kellikanvas.dream

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

open class KelliKanvasDreamService : DreamService() {
    private var session: DreamSession? = null

    protected open fun createSlideshowHost(): DreamSlideshowHost = DreamSlideshowHost.Unavailable

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        configureDreamWindow(window)

        val root = window?.decorView as? ViewGroup
        if (root == null) {
            finish()
            return
        }
        session =
            DreamSession(createSlideshowHost()).also {
                it.start(root, ::finish)
            }
    }

    override fun onDetachedFromWindow() {
        session?.stop()
        session = null
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

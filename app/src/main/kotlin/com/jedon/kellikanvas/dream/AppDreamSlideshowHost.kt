package com.jedon.kellikanvas.dream

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.jedon.kellikanvas.KelliKanvasApp
import com.jedon.kellikanvas.ShellState
import com.jedon.kellikanvas.feature.slideshow.SimpleSlideshowScreen
import com.jedon.kellikanvas.loadShellState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Plays the saved collection inside the system dream using the same slideshow as the app.
 */
internal class AppDreamSlideshowHost(
    private val app: KelliKanvasApp,
) : DreamSlideshowHost {
    private var playable: ShellState? = null
    private var slideDurationMillis: Long = 15_000
    private var composeView: ComposeView? = null

    override fun hasPlayableCollection(): Boolean {
        val container =
            try {
                app.container
            } catch (_: UninitializedPropertyAccessException) {
                return false
            }
        val loaded =
            runBlocking(Dispatchers.IO) {
                val state = loadShellState(container)
                val duration =
                    container.preferences.preferences.first().appPreferences.slideDurationMillis
                state to duration
            }
        val state = loaded.first
        slideDurationMillis = loaded.second
        playable = state.takeIf { it.roots.isNotEmpty() && it.adapters.isNotEmpty() }
        return playable != null
    }

    override fun attach(container: ViewGroup) {
        val state = playable ?: return
        if (composeView != null) return
        val view =
            ComposeView(container.context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setContent {
                    SimpleSlideshowScreen(
                        adapters = state.adapters,
                        roots = state.roots,
                        slideDurationMillis = slideDurationMillis,
                        onExit = {},
                    )
                }
            }
        container.addView(view)
        composeView = view
    }

    override fun detach() {
        val view = composeView
        composeView = null
        playable = null
        view?.let { child ->
            (child.parent as? ViewGroup)?.removeView(child)
            child.disposeComposition()
        }
    }
}

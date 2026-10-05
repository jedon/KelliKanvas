package com.jedon.kellikanvas.renderer.surface

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import com.jedon.kellikanvas.model.TransitionType

/** Two uploaded photo textures: animate composition without redrawing 4K pixels every tick. */
class PhotoTransitionView(context: Context) : FrameLayout(context) {
    private val first = PhotoSurfaceView(context)
    private val second = PhotoSurfaceView(context)
    private var current: PhotoSurfaceView? = null
    private var incoming: PhotoSurfaceView? = null
    private var animation: AnimatorSet? = null
    private var generation = 0

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        listOf(first, second).forEach { view ->
            view.alpha = 0f
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setFixedPanelSize(widthPx: Int, heightPx: Int) {
        first.setFixedPanelSize(widthPx, heightPx)
        second.setFixedPanelSize(widthPx, heightPx)
    }

    fun showFrame(bitmap: Bitmap) = transitionTo(bitmap, TransitionType.CUT, 0) {}

    /** Completion means the outgoing texture no longer references its bitmap. */
    fun transitionTo(bitmap: Bitmap, type: TransitionType, durationMillis: Long, onComplete: () -> Unit) {
        cancelTransition()
        val ticket = ++generation
        val previous = current
        val next = if (previous === first) second else first
        incoming = next
        reset(next)
        next.alpha = 1f
        // Upload behind the opaque current photo; a zero-alpha TextureView may skip updates.
        if (previous == null) next.bringToFront() else previous.bringToFront()
        // Do not start fading/sliding until the next texture has actually reached the compositor.
        next.showFrame(bitmap) {
            if (ticket != generation) return@showFrame
            next.bringToFront()
            next.alpha = if (previous == null) 1f else 0f
            val resolved = if (type == TransitionType.RANDOM) listOf(TransitionType.CROSSFADE, TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT, TransitionType.PAN_ZOOM).random() else type
            fun finish() {
                if (ticket != generation) return
                previous?.let { old ->
                    old.alpha = 0f
                    old.clearFrame()
                    reset(old)
                }
                reset(next)
                next.alpha = 1f
                current = next
                incoming = null
                animation = null
                onComplete()
            }
            if (previous == null || resolved == TransitionType.CUT || durationMillis <= 0) {
                finish()
                return@showFrame
            }
            fun property(view: PhotoSurfaceView, name: String, from: Float, to: Float) = ObjectAnimator.ofFloat(view, name, from, to)
            val set = AnimatorSet()
            when (resolved) {
                TransitionType.FADE_THROUGH_BLACK -> set.playSequentially(
                    property(previous, "alpha", 1f, 0f),
                    property(next, "alpha", 0f, 1f),
                )
                TransitionType.SLIDE_LEFT, TransitionType.SLIDE_RIGHT -> {
                    val distance = width.toFloat() * if (resolved == TransitionType.SLIDE_LEFT) 1f else -1f
                    next.alpha = 1f
                    next.translationX = distance
                    set.playTogether(property(next, "translationX", distance, 0f), property(previous, "translationX", 0f, -distance))
                }
                TransitionType.PAN_ZOOM -> {
                    next.scaleX = 1.06f
                    next.scaleY = 1.06f
                    next.translationX = width * .015f
                    set.playTogether(property(next, "alpha", 0f, 1f), property(next, "scaleX", 1.06f, 1f), property(next, "scaleY", 1.06f, 1f), property(next, "translationX", width * .015f, 0f))
                }
                else -> set.playTogether(property(next, "alpha", 0f, 1f))
            }
            set.duration = if (resolved == TransitionType.FADE_THROUGH_BLACK) (durationMillis / 2).coerceAtLeast(1) else durationMillis
            set.interpolator = AccelerateDecelerateInterpolator()
            set.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            animation = set
            set.start()
        }
    }

    /** A rapid skip restores the last committed photo, never a blank or partially loaded frame. */
    fun cancelTransition() {
        generation++
        animation?.removeAllListeners()
        animation?.cancel()
        animation = null
        incoming?.let { next ->
            next.alpha = 0f
            next.clearFrame()
            reset(next)
        }
        incoming = null
        current?.let { previous ->
            reset(previous)
            previous.alpha = 1f
        }
    }

    fun clearFrame() {
        cancelTransition()
        listOf(first, second).forEach { view ->
            view.alpha = 0f
            view.clearFrame()
        }
        current = null
    }

    private fun reset(view: PhotoSurfaceView) {
        view.translationX = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}

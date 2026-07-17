package com.jedon.kellikanvas.platform.ambient

import android.view.Window
import android.view.WindowManager

class WindowBrightnessSink(
    private val window: Window,
) : BrightnessSink {
    override fun apply(decision: BrightnessDecision) {
        val brightness =
            when (decision) {
                is BrightnessDecision.Sensor -> decision.brightness
                is BrightnessDecision.Schedule -> decision.brightness
                BrightnessDecision.FollowTv ->
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        val attributes = window.attributes
        attributes.screenBrightness = brightness
        window.attributes = attributes
    }
}

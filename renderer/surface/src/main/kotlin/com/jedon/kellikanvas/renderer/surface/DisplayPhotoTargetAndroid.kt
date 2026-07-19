package com.jedon.kellikanvas.renderer.surface

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager

/**
 * Resolve the still-photo decode long-edge from the physical display mode.
 * On a 4K TV this returns 3840 when that mode is advertised — never an arbitrary 1920 cap.
 */
fun Context.slideshowDecodeLongEdgePx(): Int {
    val television = isTelevisionFormFactor()
    val windowManager =
        getSystemService(WindowManager::class.java)
            ?: return DisplayPhotoTarget.decodeLongEdgePx(
                DisplaySize(
                    resources.displayMetrics.widthPixels.coerceAtLeast(1),
                    resources.displayMetrics.heightPixels.coerceAtLeast(1),
                ),
                television,
            )
    val current = currentDisplaySize(windowManager)
    val available = supportedDisplaySizes(windowManager)
    val selected = DisplayPhotoTarget.preferMode(available, current)
    return DisplayPhotoTarget.decodeLongEdgePx(selected, television)
}

fun Context.isTelevisionFormFactor(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }
    val pm = packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)
}

private fun Context.currentDisplaySize(windowManager: WindowManager): DisplaySize {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)
        val mode = display?.mode
        if (mode != null) {
            val mw = mode.physicalWidth.coerceAtLeast(1)
            val mh = mode.physicalHeight.coerceAtLeast(1)
            if (mw >= w && mh >= h) {
                return DisplaySize(mw, mh)
            }
        }
        return DisplaySize(w, h)
    }
    @Suppress("DEPRECATION")
    val legacyDisplay = windowManager.defaultDisplay
    val mode = legacyDisplay.mode
    return DisplaySize(
        mode.physicalWidth.coerceAtLeast(1),
        mode.physicalHeight.coerceAtLeast(1),
    )
}

private fun Context.supportedDisplaySizes(windowManager: WindowManager): List<DisplaySize> {
    val resolvedDisplay =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return emptyList()
    return resolvedDisplay.supportedModes.map { mode ->
        DisplaySize(
            mode.physicalWidth.coerceAtLeast(1),
            mode.physicalHeight.coerceAtLeast(1),
        )
    }
}

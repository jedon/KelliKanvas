package com.jedon.kellikanvas.home

import android.view.KeyEvent

const val PAGE_MENU = 0
const val PAGE_HOME = 1
const val PAGE_COLLECTION = 2
const val PAGE_COUNT = 3

/**
 * Maps DPAD left/right to a pager page index, or null when the key should not change pages.
 */
fun targetPageForDpad(
    currentPage: Int,
    pageCount: Int,
    keyCode: Int,
): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> if (currentPage > 0) currentPage - 1 else null
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (currentPage < pageCount - 1) currentPage + 1 else null
    else -> null
}

/**
 * Maps joystick/hat AXIS_HAT_X values to a pager page index.
 * Negative hat X is left; positive is right; near-zero is neutral.
 */
fun targetPageForHatX(
    currentPage: Int,
    pageCount: Int,
    hatX: Float,
): Int? = when {
    hatX <= -0.5f -> targetPageForDpad(currentPage, pageCount, KeyEvent.KEYCODE_DPAD_LEFT)
    hatX >= 0.5f -> targetPageForDpad(currentPage, pageCount, KeyEvent.KEYCODE_DPAD_RIGHT)
    else -> null
}

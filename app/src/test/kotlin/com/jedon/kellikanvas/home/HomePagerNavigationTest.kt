package com.jedon.kellikanvas.home

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomePagerNavigationTest {
    @Test
    fun dpadLeftFromHomeGoesToMenu() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            ),
        ).isEqualTo(PAGE_MENU)
    }

    @Test
    fun dpadRightFromHomeGoesToCollection() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        ).isEqualTo(PAGE_COLLECTION)
    }

    @Test
    fun dpadLeftFromMenuStaysNull() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_MENU,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            ),
        ).isNull()
    }

    @Test
    fun dpadRightFromCollectionStaysNull() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_COLLECTION,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        ).isNull()
    }

    @Test
    fun dpadRightFromMenuGoesToHome() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_MENU,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        ).isEqualTo(PAGE_HOME)
    }

    @Test
    fun dpadLeftFromCollectionGoesToHome() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_COLLECTION,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            ),
        ).isEqualTo(PAGE_HOME)
    }

    @Test
    fun unrelatedKeyReturnsNull() {
        assertThat(
            targetPageForDpad(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                keyCode = KeyEvent.KEYCODE_DPAD_UP,
            ),
        ).isNull()
    }

    @Test
    fun hatLeftMapsLikeDpadLeft() {
        assertThat(
            targetPageForHatX(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                hatX = -1f,
            ),
        ).isEqualTo(PAGE_MENU)
    }

    @Test
    fun hatRightMapsLikeDpadRight() {
        assertThat(
            targetPageForHatX(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                hatX = 1f,
            ),
        ).isEqualTo(PAGE_COLLECTION)
    }

    @Test
    fun hatNeutralReturnsNull() {
        assertThat(
            targetPageForHatX(
                currentPage = PAGE_HOME,
                pageCount = PAGE_COUNT,
                hatX = 0f,
            ),
        ).isNull()
    }

    @Test
    fun centerSelectFromHomeOpensMenu() {
        assertThat(
            targetPageForCenterSelect(
                currentPage = PAGE_HOME,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            ),
        ).isEqualTo(PAGE_MENU)
        assertThat(
            targetPageForCenterSelect(
                currentPage = PAGE_HOME,
                keyCode = KeyEvent.KEYCODE_ENTER,
            ),
        ).isEqualTo(PAGE_MENU)
    }

    @Test
    fun centerSelectFromOtherPagesIgnored() {
        assertThat(
            targetPageForCenterSelect(
                currentPage = PAGE_COLLECTION,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            ),
        ).isNull()
    }
}

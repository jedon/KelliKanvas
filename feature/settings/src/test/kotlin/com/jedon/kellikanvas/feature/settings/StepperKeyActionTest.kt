package com.jedon.kellikanvas.feature.settings

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepperKeyActionTest {
    @Test
    fun left_decrementsWhenDecrementEnabled() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                decrementEnabled = true,
                incrementEnabled = true,
            ),
        ).isEqualTo(StepperKeyAction.DECREMENT)
    }

    @Test
    fun left_passesThroughAtLowerBoundSoFocusCanLeaveTheRow() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                decrementEnabled = false,
                incrementEnabled = true,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
    }

    @Test
    fun right_incrementsWhenIncrementEnabled() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                decrementEnabled = false,
                incrementEnabled = true,
            ),
        ).isEqualTo(StepperKeyAction.INCREMENT)
    }

    @Test
    fun right_passesThroughAtUpperBound() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                decrementEnabled = true,
                incrementEnabled = false,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
    }

    @Test
    fun bothDisabled_passesThroughInBothDirections() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                decrementEnabled = false,
                incrementEnabled = false,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                decrementEnabled = false,
                incrementEnabled = false,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
    }

    @Test
    fun unrelatedKeys_alwaysPassThrough() {
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_UP,
                decrementEnabled = true,
                incrementEnabled = true,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
        assertThat(
            stepperKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                decrementEnabled = true,
                incrementEnabled = true,
            ),
        ).isEqualTo(StepperKeyAction.PASS)
    }
}

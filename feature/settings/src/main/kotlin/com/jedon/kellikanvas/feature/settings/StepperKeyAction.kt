package com.jedon.kellikanvas.feature.settings

import android.view.KeyEvent

/** What a TV stepper row should do with a D-pad key press. */
internal enum class StepperKeyAction {
    DECREMENT,
    INCREMENT,

    /** Not actionable here; leave the event unconsumed so focus search can move on. */
    PASS,
}

/**
 * Pure decision for the stepper row's key handling: consume Left/Right only when the
 * corresponding step is actually possible. At bounds (or when disabled) the key must
 * pass through, e.g. so Left can still reach the TV navigation drawer.
 */
internal fun stepperKeyAction(
    keyCode: Int,
    decrementEnabled: Boolean,
    incrementEnabled: Boolean,
): StepperKeyAction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT ->
        if (decrementEnabled) StepperKeyAction.DECREMENT else StepperKeyAction.PASS
    KeyEvent.KEYCODE_DPAD_RIGHT ->
        if (incrementEnabled) StepperKeyAction.INCREMENT else StepperKeyAction.PASS
    else -> StepperKeyAction.PASS
}

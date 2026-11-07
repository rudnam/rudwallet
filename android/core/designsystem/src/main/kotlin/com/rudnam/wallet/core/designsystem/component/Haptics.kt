package com.rudnam.wallet.core.designsystem.component

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's haptics: one kind per moment, so each feels the same every
 * time, and each the system's own feedback for that moment (the nearest
 * older one on older Android). They follow the phone's touch feedback
 * setting. Ordinary taps don't buzz.
 */
@Stable
class Haptics(private val view: View) {
    /** A key on the number pad, as the phone's keyboard feels. */
    fun key() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A choice: a category, an account, expense or income. */
    fun select() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** A swipe passing the point where letting go acts. */
    fun threshold() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK)

    /** Something saved or confirmed. */
    fun success() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)

    /** Something that couldn't be done, such as saving without an amount. */
    fun error() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(feedback: Int) {
        view.performHapticFeedback(feedback)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

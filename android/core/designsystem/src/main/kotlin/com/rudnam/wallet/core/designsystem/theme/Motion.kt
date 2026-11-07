package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * The app's motion, after Material 3's emphasized easing: things set off quickly (so a tap
 * answers at once) and settle gently. Kept short, so nothing waits on an animation.
 */
object WalletMotion {
    /** Coming in: fast at first, a long gentle stop. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Going away: a soft start, then quickly gone. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A page changing, and a card growing into its page. */
    const val PAGE = 350

    /** What leaves fades this fast, so the new page is seen almost at once. */
    const val FADE_OUT = 100
}

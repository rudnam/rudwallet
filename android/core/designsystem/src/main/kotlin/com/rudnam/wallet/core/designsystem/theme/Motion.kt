package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The app's motion. Pages and the tab bar move on springs, so they feel like they have weight:
 * they set off at once and settle into place. Things that only change
 * (a fade) use short, eased tweens. Kept quick, so nothing waits on an animation.
 */
object WalletMotion {
    /** Coming in: fast at first, a long gentle stop. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Going away: a soft start, then quickly gone. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * A whole page moving across the screen. No bounce: a page that overshot would open a
     * gap at its edge.
     */
    fun <T> pageSpring(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, PAGE_STIFFNESS)

    /**
     * A piece moving on its own (an account's card growing into its page, and back into Wallet).
     * A set time, not a spring: a spring goes on settling by fractions of a pixel well after it
     * looks done, and the pages' transition only ends when it stops.
     */
    fun <T> piece(): FiniteAnimationSpec<T> = tween(PIECE, easing = EmphasizedDecelerate)

    /** A page fading in over another (an account's page around its card). */
    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(FADE_IN, delayMillis = FADE_DELAY, easing = EmphasizedDecelerate)

    /** A page fading away above another. */
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(FADE_OUT, easing = EmphasizedDecelerate)

    private const val PAGE_STIFFNESS = 450f
    /**
     * Under the back gesture, which follows the finger: how far it's dragged is how far along
     * the transition is. So there things run evenly; a spring or an easing would play most of
     * it in the first part of the drag.
     */
    fun <T> dragged(): FiniteAnimationSpec<T> = tween(DRAGGED, easing = LinearEasing)

    private const val PIECE = 400
    private const val DRAGGED = 300
    private const val FADE_IN = 250
    private const val FADE_DELAY = 50
    private const val FADE_OUT = 220
}

package com.rudnam.wallet.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The app's motion. Things that move (pages, cards, the tab bar) move on springs, so they feel
 * like they have weight: they set off at once and settle into place. Things that only change
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

    /** A piece moving on its own (an account's card growing into its page): settles with a slight give. */
    fun <T> pieceSpring(): FiniteAnimationSpec<T> = spring(PIECE_DAMPING, PIECE_STIFFNESS)

    /** A page fading in over another (an account's page around its card). */
    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(FADE_IN, delayMillis = FADE_DELAY, easing = EmphasizedDecelerate)

    /** A page fading away above another. */
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(FADE_OUT, easing = EmphasizedDecelerate)

    private const val PAGE_STIFFNESS = 450f
    private const val PIECE_STIFFNESS = 380f
    private const val PIECE_DAMPING = 0.82f
    private const val FADE_IN = 250
    private const val FADE_DELAY = 50
    private const val FADE_OUT = 220
}

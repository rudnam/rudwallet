package com.rudnam.wallet.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rudnam.wallet.core.designsystem.theme.WalletMotion.EmphasizedAccelerate
import com.rudnam.wallet.core.designsystem.theme.WalletMotion.EmphasizedDecelerate
import com.rudnam.wallet.core.designsystem.theme.WalletMotion.FADE_OUT
import com.rudnam.wallet.core.designsystem.theme.WalletMotion.PAGE

// How pages change, after Material's motion, with its emphasized easing (quick to set off, gentle
// to stop): between the tabs, a fade through (the old tab fades quickly, the new one fades in
// growing slightly); for a page opened on top of one, a shared axis (both slide a little, the
// new one in from the side it goes forward to). The back gesture plays the pop ones as it's
// dragged (predictive back). Navigation Compose's default, a 700 ms cross-fade, felt slow.

// How far a page slides, as a share of the screen's width: a nudge, not the whole width.
private const val SLIDE_SHARE = 8

// The size a tab grows from as it fades in.
private const val TAB_SCALE = 0.94f

private fun NavBackStackEntry.isTab() = TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() = initialState.isTab() && targetState.isTab()

private val fadeIn = fadeIn(tween(PAGE - FADE_OUT, delayMillis = FADE_OUT, easing = EmphasizedDecelerate))
private val fadeOut = fadeOut(tween(FADE_OUT, easing = EmphasizedAccelerate))

private val tabIn = fadeIn + scaleIn(tween(PAGE - FADE_OUT, delayMillis = FADE_OUT, easing = EmphasizedDecelerate), initialScale = TAB_SCALE)

/** A page coming in: from the end going forward, from the start coming back. */
private fun slideIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(PAGE, easing = EmphasizedDecelerate)) { width -> (if (forward) width else -width) / SLIDE_SHARE } + fadeIn

private fun slideOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(PAGE, easing = EmphasizedDecelerate)) { width -> (if (forward) -width else width) / SLIDE_SHARE } + fadeOut

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (betweenTabs()) tabIn else slideIn(forward = true)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (betweenTabs()) fadeOut else slideOut(forward = true)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (betweenTabs()) tabIn else slideIn(forward = false)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (betweenTabs()) fadeOut else slideOut(forward = false)

package com.rudnam.wallet.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

// How screens change, after Material's motion: a quick fade through between the tabs, and a
// short shared-axis slide for a page opened on top of one. Navigation Compose's default is a
// 700 ms cross-fade on every change, which makes even a tab switch feel slow.

private const val DURATION = 300
private const val FADE_OUT = 90

// How far a page slides, as a share of the screen's width: a nudge, not the whole width.
private const val SLIDE_SHARE = 10

private fun NavBackStackEntry.isTab() = TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() = initialState.isTab() && targetState.isTab()

private val fadeThroughIn = fadeIn(tween(DURATION - FADE_OUT, delayMillis = FADE_OUT, easing = LinearOutSlowInEasing))
private val fadeThroughOut = fadeOut(tween(FADE_OUT, easing = FastOutLinearInEasing))

/** A page coming in: from the end going forward, from the start coming back. */
private fun slideIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { width -> (if (forward) width else -width) / SLIDE_SHARE } + fadeThroughIn

private fun slideOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { width -> (if (forward) -width else width) / SLIDE_SHARE } + fadeThroughOut

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (betweenTabs()) fadeThroughIn else slideIn(forward = true)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (betweenTabs()) fadeThroughOut else slideOut(forward = true)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (betweenTabs()) fadeThroughIn else slideIn(forward = false)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (betweenTabs()) fadeThroughOut else slideOut(forward = false)

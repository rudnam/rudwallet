package com.rudnam.wallet.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rudnam.wallet.core.designsystem.theme.WalletMotion
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute

// How pages change. One thing moves at a time, and not far, so going around the app is calm:
//
// - A page opened on top of another comes in over it: it slides a short way in from the right
//   as it fades in, and the page underneath stays where it is. Closing it, it slides the same
//   short way back and fades, uncovering the page underneath, still where it was. The back
//   gesture plays the closing under your finger (predictive back), only that short way.
//   It goes the way the finger does: swiped from the right edge, it slides to the left.
// - Between the tabs, the new one comes from the side its tab is on: it slides in a short way
//   as it fades in, and the old one drifts a little the other way as it fades. A nudge, so the
//   tabs have a direction without the whole screen swinging round.
// - An account's page opens around its card, which grows out of Wallet (core/ui's
//   SharedElements.kt): the page only fades, so the card is what moves.
//
// Every page draws its own ground (core/ui's Page), so a page fading in over another covers it.

/** How far a page slides, as a share of the screen's width. */
private const val SLIDE_SHARE = 6

/** How quickly the old tab fades, and then the new one comes in. */
private const val TAB_OUT = 90
private const val TAB_IN = 210

/** How far a new tab slides in, and the old one drifts, as shares of the screen's width. */
private const val TAB_NUDGE = 10
private const val TAB_DRIFT = 20

/** The entry's place in the tab bar, or -1 for a page that isn't a tab. */
private fun NavBackStackEntry.tab() = TopLevelDestination.entries.indexOfFirst { destination.hasRoute(it.route::class) }

private fun NavBackStackEntry.isTab() = tab() >= 0

private fun NavBackStackEntry.isAccount() = destination.hasRoute(AccountRoute::class)

private fun NavBackStackEntry.isWallet() = destination.hasRoute(WalletRoute::class)

private enum class Kind { Tabs, Stack, OverCard }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.kind(): Kind = when {
    (initialState.isWallet() && targetState.isAccount()) || (initialState.isAccount() && targetState.isWallet()) -> Kind.OverCard
    initialState.isTab() && targetState.isTab() -> Kind.Tabs
    else -> Kind.Stack
}

// A page on top, coming in over the one underneath and going away from it.
private val coverIn =
    slideInHorizontally(WalletMotion.pageSpring()) { it / SLIDE_SHARE } + fadeIn(WalletMotion.pageSpring())
private val coverOut =
    slideOutHorizontally(WalletMotion.pageSpring()) { it / SLIDE_SHARE } + fadeOut(WalletMotion.pageSpring())

// Under the back gesture, the transition follows the finger, so it runs evenly
// (WalletMotion.dragged), and the page goes the way the finger does.
private fun coverDragged(toLeft: Boolean) =
    slideOutHorizontally(WalletMotion.dragged()) { (if (toLeft) -it else it) / SLIDE_SHARE } + fadeOut(WalletMotion.dragged())

private val fadeDragged = fadeOut(WalletMotion.dragged())

// An account's page, around its card.
private val fadeOver = fadeIn(WalletMotion.fadeIn())
private val fadeAway = fadeOut(WalletMotion.fadeOut())

/** Between tabs: whether the new one is to the right of the old one in the tab bar. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.toTheRight() = targetState.tab() > initialState.tab()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabIn(): EnterTransition {
    val side = if (toTheRight()) 1 else -1
    return slideInHorizontally(WalletMotion.pageSpring()) { side * it / TAB_NUDGE } +
        fadeIn(tween(TAB_IN, delayMillis = TAB_OUT, easing = WalletMotion.EmphasizedDecelerate))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabOut(): ExitTransition {
    val side = if (toTheRight()) -1 else 1
    return slideOutHorizontally(WalletMotion.pageSpring()) { side * it / TAB_DRIFT } +
        fadeOut(tween(TAB_OUT, easing = WalletMotion.EmphasizedAccelerate))
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition = when (kind()) {
    Kind.Tabs -> tabIn()
    Kind.Stack -> coverIn
    Kind.OverCard -> fadeOver
}

// The page underneath stays as it is, under the one coming in, till that one has covered it.
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition = when (kind()) {
    Kind.Tabs -> tabOut()
    Kind.Stack, Kind.OverCard -> ExitTransition.KeepUntilTransitionsFinished
}

// The page underneath is already in place as the one above goes.
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition = when (kind()) {
    Kind.Tabs -> tabIn()
    Kind.Stack, Kind.OverCard -> EnterTransition.None
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition = when (kind()) {
    Kind.Tabs -> tabOut()
    Kind.Stack -> coverOut
    Kind.OverCard -> fadeAway
}

/** Navigation's swipe edge for a back gesture from the right edge of the screen. */
private const val EDGE_RIGHT = 1

/** [popExit] under the back gesture, [swipeEdge] the edge it started from. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.predictivePopExit(swipeEdge: Int): ExitTransition = when (kind()) {
    Kind.Tabs -> tabOut()
    Kind.Stack -> coverDragged(toLeft = swipeEdge == EDGE_RIGHT)
    Kind.OverCard -> fadeDragged
}

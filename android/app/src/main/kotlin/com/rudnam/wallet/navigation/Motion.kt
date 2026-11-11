package com.rudnam.wallet.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rudnam.wallet.core.designsystem.theme.WalletMotion
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.signin.navigation.SignInRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute

// How pages change. The pages are sheets of paper on a stack, and they move on springs:
//
// - A page opened on top of another slides in over it from the right, at full width; the one
//   under it drifts back a little the other way and dims, as if it went into shade. Closing
//   it does the reverse. The back gesture plays the closing one under your finger
//   (predictive back), so the page you drag shows the one under it coming out of the shade.
// - Between the tabs, the pages lie side by side in the tab bar's order: the new one slides in
//   from the side its tab is on, pushing the old one out the other way.
// - An account's page opens around its card, which grows out of Wallet (SharedElements.kt):
//   the page fades in over Wallet rather than sliding, so the card is what moves.
// - The sign-in page and the app fade into each other.
//
// The dimming is the page under fading partly out over the black that the NavHost draws
// behind all the pages (RudWalletApp.kt). Each page draws its own ground (core/ui's Page), so
// in every other case that black is covered.

/** How far the page underneath drifts back, as a share of the screen's width. */
private const val DRIFT = 0.3f

/** How much of the page underneath still shows through the shade. */
private const val SHADE = 0.75f

private fun NavBackStackEntry.tab(): Int = TopLevelDestination.entries.indexOfFirst { destination.hasRoute(it.route::class) }

private fun NavBackStackEntry.isAccount() = destination.hasRoute(AccountRoute::class)

private fun NavBackStackEntry.isWallet() = destination.hasRoute(WalletRoute::class)

private fun NavBackStackEntry.isSignIn() = destination.hasRoute(SignInRoute::class)

private enum class Kind { Tabs, Stack, OverCard, Fade }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.kind(): Kind = when {
    initialState.isSignIn() || targetState.isSignIn() -> Kind.Fade
    (initialState.isWallet() && targetState.isAccount()) || (initialState.isAccount() && targetState.isWallet()) -> Kind.OverCard
    initialState.tab() >= 0 && targetState.tab() >= 0 -> Kind.Tabs
    else -> Kind.Stack
}

/** Between tabs: true when the new tab is to the right of the old one. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.toTheRight() = targetState.tab() > initialState.tab()

private fun drift(width: Int) = (width * DRIFT).toInt()

// A page on top, sliding over the one underneath.
private val coverIn = slideInHorizontally(WalletMotion.pageSpring()) { it }
private val coverOut = slideOutHorizontally(WalletMotion.pageSpring()) { it }

// The page underneath, drifting back into the shade and out of it.
private val shadeOut = slideOutHorizontally(WalletMotion.pageSpring()) { -drift(it) } + fadeOut(WalletMotion.pageSpring(), targetAlpha = SHADE)
private val shadeIn = slideInHorizontally(WalletMotion.pageSpring()) { -drift(it) } + fadeIn(WalletMotion.pageSpring(), initialAlpha = SHADE)

// Tabs, side by side.
private fun tabIn(fromRight: Boolean) = slideInHorizontally(WalletMotion.pageSpring()) { if (fromRight) it else -it }
private fun tabOut(toLeft: Boolean) = slideOutHorizontally(WalletMotion.pageSpring()) { if (toLeft) -it else it }

// One page fading in over another, which stays as it is until it's covered (and the
// reverse: the page on top fading away from the one underneath).
private val fadeOver = fadeIn(WalletMotion.fadeIn())
private val fadeAway = fadeOut(WalletMotion.fadeOut())

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition = when (kind()) {
    Kind.Tabs -> tabIn(fromRight = toTheRight())
    Kind.Stack -> coverIn
    Kind.OverCard, Kind.Fade -> fadeOver
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition = when (kind()) {
    Kind.Tabs -> tabOut(toLeft = toTheRight())
    Kind.Stack -> shadeOut
    Kind.OverCard, Kind.Fade -> ExitTransition.KeepUntilTransitionsFinished
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition = when (kind()) {
    Kind.Tabs -> tabIn(fromRight = toTheRight())
    Kind.Stack -> shadeIn
    Kind.OverCard, Kind.Fade -> EnterTransition.None
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition = when (kind()) {
    Kind.Tabs -> tabOut(toLeft = toTheRight())
    Kind.Stack -> coverOut
    Kind.OverCard, Kind.Fade -> fadeAway
}

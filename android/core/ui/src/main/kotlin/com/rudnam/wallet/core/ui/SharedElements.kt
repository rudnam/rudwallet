package com.rudnam.wallet.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.rudnam.wallet.core.designsystem.theme.WalletMotion
import com.rudnam.wallet.core.designsystem.theme.WalletTheme

/**
 * Shared elements between pages: a piece of one page that becomes a piece of the next, like an
 * account's card on Wallet growing into the card at the top of its page. The app wraps its
 * pages in a SharedTransitionLayout and provides it here; each page provides its own
 * AnimatedVisibilityScope ([Page]). Without them (previews, tests), nothing is shared.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The page's own scope in the navigation's animation; [Page] provides it. */
val LocalPageAnimationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A destination's content, as a page: an opaque sheet on its own ground, so a page sliding
 * over another covers it (the app's Motion.kt), and able to share elements with the page it
 * came from or goes to. Every destination's content goes in one.
 */
@Composable
fun AnimatedVisibilityScope.Page(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageAnimationScope provides this) {
        Box(Modifier.fillMaxSize().background(WalletTheme.colors.ground)) { content() }
    }
}

/** The key an account's card shares between Wallet and the account's page. */
fun accountCardKey(id: Long): Any = "account-card-$id"

@OptIn(ExperimentalSharedTransitionApi::class)
private val PageBounds = BoundsTransform { _, _ -> WalletMotion.pieceSpring() }

/**
 * This piece moves and resizes into the piece with the same [key] on the next page (their
 * content cross-fades if it differs), drawn above both pages while it does.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBounds(key: Any, shape: Shape, zIndexInOverlay: Float = 0f): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val page = LocalPageAnimationScope.current ?: return this
    return with(shared) {
        this@sharedBounds.sharedBounds(
            rememberSharedContentState(key),
            page,
            boundsTransform = PageBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}

/**
 * [sharedBounds] for a piece in a stack (Wallet's cards), [zIndex] its place in it, the front
 * one highest. A shared piece is drawn above both pages while it moves, so one coming back
 * into the stack would cover the pieces in front of it until it landed, then drop behind
 * them. So while its page comes back, the stack's other pieces are drawn above the pages
 * too, each at its place, fading in as their page does.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBoundsInStack(key: Any, shape: Shape, zIndex: Float): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val page = LocalPageAnimationScope.current ?: return this
    val state = shared.rememberSharedContentState(key)
    // In step with the account's page fading away above Wallet (navigation's Motion.kt), so
    // these come out from under it as it goes.
    val fade = page.transition.animateFloat(
        transitionSpec = { WalletMotion.fadeOut() },
        label = "stack",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val above = { shared.isTransitionActive && page.transition.targetState == EnterExitState.Visible && !state.isMatchFound }
    return with(shared) {
        this@sharedBoundsInStack
            .renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndex, renderInOverlay = above)
            .graphicsLayer { alpha = if (above()) fade.value else 1f }
            .sharedBounds(
                state,
                page,
                boundsTransform = PageBounds,
                clipInOverlayDuringTransition = OverlayClip(shape),
                zIndexInOverlay = zIndex,
            )
    }
}

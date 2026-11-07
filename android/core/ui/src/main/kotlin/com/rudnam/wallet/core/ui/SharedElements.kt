package com.rudnam.wallet.core.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.rudnam.wallet.core.designsystem.theme.WalletMotion

/**
 * Shared elements between pages: a piece of one page that becomes a piece of the next, like an
 * account's card on Wallet growing into the card at the top of its page. The app wraps its
 * pages in a SharedTransitionLayout and provides it here; each page provides its own
 * AnimatedVisibilityScope ([SharedPage]). Without them (previews, tests), nothing is shared.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The page's own scope in the navigation's animation; [SharedPage] provides it. */
val LocalPageAnimationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** A page's content, able to share elements with the page it came from or goes to. */
@Composable
fun AnimatedVisibilityScope.SharedPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageAnimationScope provides this, content = content)
}

/** The key an account's card shares between Wallet and the account's page. */
fun accountCardKey(id: Long): Any = "account-card-$id"

@OptIn(ExperimentalSharedTransitionApi::class)
private val PageBounds = BoundsTransform { _, _ -> tween(WalletMotion.PAGE, easing = WalletMotion.EmphasizedDecelerate) }

/**
 * This piece moves and resizes into the piece with the same [key] on the next page (their
 * content cross-fades if it differs), drawn above both pages while it does.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBounds(key: Any, shape: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val page = LocalPageAnimationScope.current ?: return this
    return with(shared) {
        this@sharedBounds.sharedBounds(
            rememberSharedContentState(key),
            page,
            boundsTransform = PageBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

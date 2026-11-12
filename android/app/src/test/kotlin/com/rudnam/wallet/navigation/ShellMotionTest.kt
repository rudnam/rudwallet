package com.rudnam.wallet.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.testing.TestNavHostController
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletMotion
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.ui.LocalSharedTransitionScope
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.core.ui.accountCardKey
import com.rudnam.wallet.core.ui.sharedBounds
import com.rudnam.wallet.core.ui.sharedBoundsInStack
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Going back from an account's page into Wallet, in a stand-in built like the app
 * (RudWalletApp): a Scaffold whose tab bar comes back as Wallet does, Wallet's cards in a
 * stack inside a list, and the account's page its own Scaffold with a top bar over a list.
 * Wallet is blue, the account's page red, the cards green, the tab bar grey. Writes its
 * timeline and frames to build/outputs/roborazzi/motion/, which CI keeps.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class ShellMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: TestNavHostController
    private lateinit var back: OnBackPressedDispatcher
    private val dir = File("build/outputs/roborazzi/motion").apply { mkdirs() }
    private val timeline = mutableListOf<String>()

    @Before
    fun setUp() {
        compose.setContent {
            WalletTheme(darkTheme = false) {
                val context = LocalContext.current
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
                val entry by nav.currentBackStackEntryAsState()
                val onWallet = entry?.destination?.hasRoute(WalletRoute::class) ?: true
                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        AnimatedVisibility(
                            visible = onWallet,
                            enter = expandVertically(WalletMotion.pageSpring<IntSize>(), expandFrom = Alignment.Top) + slideInVertically(WalletMotion.pageSpring<IntOffset>()) { it },
                            exit = shrinkVertically(WalletMotion.pageSpring<IntSize>(), shrinkTowards = Alignment.Top) + slideOutVertically(WalletMotion.pageSpring<IntOffset>()) { it },
                        ) { Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Gray)) }
                    },
                ) { padding ->
                    SharedTransitionLayout(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                            NavHost(
                                nav,
                                startDestination = WalletRoute,
                                enterTransition = { enter() },
                                exitTransition = { exit() },
                                popEnterTransition = { popEnter() },
                                popExitTransition = { popExit() },
                                predictivePopEnterTransition = { popEnter() },
                                predictivePopExitTransition = { swipeEdge -> predictivePopExit(swipeEdge) },
                            ) {
                                composable<WalletRoute> { Page { WalletStandIn() } }
                                composable<AccountRoute> { Page { AccountStandIn() } }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WalletStandIn() {
        LazyColumn(Modifier.fillMaxSize().background(Color.Blue)) {
            item {
                Box(Modifier.fillMaxWidth().height(260.dp).padding(16.dp)) {
                    // Three cards, the back one first, each 40 dp below the one behind it.
                    for (i in 0..2) {
                        Box(
                            Modifier.zIndex(i.toFloat()).offset(y = (40 * i).dp).fillMaxWidth().height(180.dp)
                                .sharedBoundsInStack(accountCardKey(i.toLong()), Shape, i.toFloat())
                                .background(Color(0xFF00C000 + i * 0x30), Shape),
                        )
                    }
                }
            }
            items(20) { Text("Record $it", Modifier.padding(16.dp), color = Color.White) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AccountStandIn() {
        val scroll = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            containerColor = Color.Red,
            contentWindowInsets = TopAppBarDefaults.windowInsets,
            topBar = { TopAppBar(title = { Text("Account") }) },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                // The front card (id 2) opened.
                item(key = "card") { Box(Modifier.padding(16.dp).fillMaxWidth().height(200.dp).sharedBounds(accountCardKey(2), Shape).background(Color(0xFF00C060), Shape)) }
                items(30) { Text("Account record $it", Modifier.padding(16.dp), color = Color.White) }
            }
        }
    }

    /** How red against blue the screen is low down, above the tab bar: 1 for the account's page, 0 for Wallet. */
    private fun redness(): Float {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixel = image.getPixel(8, image.height - 120)
        val red = android.graphics.Color.red(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return if (red + blue == 0) 0f else red.toFloat() / (red + blue)
    }

    /** Whether the screen low down shows bare ground (no page): its light grey, not a page's red or blue. */
    private fun bare(): Boolean {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        return android.graphics.Color.green(image.getPixel(8, image.height - 120)) > 120
    }

    /** Plays [ms] in frames; the redness at each, by time. */
    private fun play(label: String, ms: Long, frames: Set<Long> = setOf(48, 112, 208, 304, 400, 608, 800, 1008)): Map<Long, Float> {
        val seen = mutableMapOf<Long, Float>()
        var t = 0L
        while (t < ms) {
            compose.mainClock.advanceTimeBy(16)
            t += 16
            val red = redness()
            seen[t] = red
            timeline += "$label ${t}ms: red=${"%.2f".format(red)}${if (bare()) " bare" else ""}"
            if (t in frames) compose.onRoot().captureRoboImage("${dir.path}/$label-$t.png")
        }
        return seen
    }

    private fun assertGoneBy(ms: Long, seen: Map<Long, Float>) {
        val at = seen.filterKeys { it >= ms }.minBy { it.key }.value
        assertTrue("the account's page still shows $ms ms after going back:\n${timeline.joinToString("\n")}", at < 0.05f)
    }

    private fun openFrontCard() {
        compose.runOnUiThread { nav.navigate(AccountRoute(2)) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("${dir.path}/shell-account.png")
    }

    @Test
    fun `back from an account with the back arrow, in the app's shell`() {
        openFrontCard()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        val seen = play("shell-button", 1500)
        File(dir, "shell-button.txt").writeText(timeline.joinToString("\n"))
        assertGoneBy(250, seen)
    }

    @Test
    fun `back from an account with the back gesture, in the app's shell`() {
        openFrontCard()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT)) }
        for (i in 1..15) {
            compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(10f * i, 300f, 0.05f * i, BackEventCompat.EDGE_LEFT)) }
            compose.mainClock.advanceTimeBy(16)
            timeline += "shell-gesture drag ${"%.2f".format(0.05f * i)}: red=${"%.2f".format(redness())}"
        }
        compose.onRoot().captureRoboImage("${dir.path}/shell-gesture-dragged.png")
        compose.runOnUiThread { back.onBackPressed() }
        val seen = play("shell-gesture", 1500)
        File(dir, "shell-gesture.txt").writeText(timeline.joinToString("\n"))
        assertGoneBy(250, seen)
    }

    @Test
    fun `opening an account, Wallet stays under its page as it fades in`() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.navigate(AccountRoute(2)) }
        play("shell-open", 600)
        File(dir, "shell-open.txt").writeText(timeline.joinToString("\n"))
        assertTrue("bare ground showed as the account's page opened (Wallet faded too):\n${timeline.joinToString("\n")}", timeline.none { it.endsWith(" bare") })
    }

    private companion object {
        val Shape = RoundedCornerShape(16.dp)
    }
}

package com.rudnam.wallet.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.ui.LocalPageIsOpen
import com.rudnam.wallet.core.ui.LocalSharedTransitionScope
import com.rudnam.wallet.core.ui.Page
import com.rudnam.wallet.core.ui.accountCardKey
import com.rudnam.wallet.core.ui.sharedBounds
import com.rudnam.wallet.core.ui.sharedBoundsInStack
import com.rudnam.wallet.feature.account.navigation.AccountRoute
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.imports.navigation.CardRoute
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
 * The pages' motion, frame by frame, on stand-ins for the real pages, with the app's own
 * transitions: Wallet is blue with its card (green) top left; an account's page red around
 * the same card, full width at the top; another page (Card, from Imports) red; another tab
 * (Activity) yellow. Pixels tell which page shows where. Each test also writes its timeline
 * to build/outputs/roborazzi/motion/, which CI keeps with the screenshots.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: TestNavHostController
    private lateinit var back: OnBackPressedDispatcher

    @Before
    fun setUp() {
        compose.setContent {
            WalletTheme(darkTheme = false) {
                val context = LocalContext.current
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalPageIsOpen provides rememberPageIsOpen(nav)) {
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
                            composable<WalletRoute> {
                                Page {
                                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                                        Box(Modifier.padding(16.dp).size(200.dp, 120.dp).sharedBoundsInStack(accountCardKey(1), RectangleShape, 0f).background(Color.Green))
                                    }
                                }
                            }
                            composable<AccountRoute> {
                                Page {
                                    Column(Modifier.fillMaxSize().background(Color.Red)) {
                                        Box(Modifier.fillMaxWidth().height(160.dp).sharedBounds(accountCardKey(1), RectangleShape).background(Color.Green))
                                    }
                                }
                            }
                            composable<CardRoute> { Page { Box(Modifier.fillMaxSize().background(Color.Red)) } }
                            composable<ActivityRoute> { Page { Box(Modifier.fillMaxSize().background(Color.Yellow)) } }
                        }
                    }
                }
            }
        }
    }

    private class Frame(val ms: Long, val bottom: Int, val left: Int, val right: Int, val cardEnd: Int)

    /** The screen now: the colour at the bottom middle, at the bottom left and right edges, and where only the account's card reaches (top right). */
    private fun frame(ms: Long): Frame {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val bottom = image.height - 20
        return Frame(ms, image.getPixel(image.width / 2, bottom), image.getPixel(4, bottom), image.getPixel(image.width - 5, bottom), image.getPixel(image.width - 20, 80))
    }

    /** How red a colour is against blue: 1 for the account's page (or Card), 0 for Wallet. */
    private fun redness(pixel: Int): Float {
        val red = android.graphics.Color.red(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return if (red + blue == 0) 0f else red.toFloat() / (red + blue)
    }

    private fun isGreen(pixel: Int) = android.graphics.Color.green(pixel) > 200 && android.graphics.Color.red(pixel) < 60

    private val timeline = mutableListOf<String>()

    private fun record(label: String, f: Frame) {
        timeline += "$label ${f.ms}ms: bottom=${"%.2f".format(redness(f.bottom))} left=${"%.2f".format(redness(f.left))} right=${"%.2f".format(redness(f.right))} cardEnd=${if (isGreen(f.cardEnd)) "card" else "-"}"
    }

    private fun save(name: String) {
        val dir = File("build/outputs/roborazzi/motion").apply { mkdirs() }
        File(dir, "$name.txt").writeText(timeline.joinToString("\n"))
    }

    private fun open(route: Any) {
        compose.runOnUiThread { nav.navigate(route) }
        compose.waitForIdle()
    }

    /** Frames every 16 ms for [ms], recorded. */
    private fun run(label: String, ms: Long, from: Long = 0): List<Frame> {
        val frames = mutableListOf<Frame>()
        var t = from
        while (t < from + ms) {
            compose.mainClock.advanceTimeBy(16)
            t += 16
            frames += frame(t).also { record(label, it) }
        }
        return frames
    }

    /** A back gesture from [edge], dragged to [progress] over [steps] frames; the frames as it's dragged. */
    private fun drag(edge: Int, progress: Float, steps: Int = 15): List<Frame> {
        compose.mainClock.autoAdvance = false
        val x = if (edge == BackEventCompat.EDGE_LEFT) 0f else 1000f
        compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(x, 300f, 0f, edge)) }
        val frames = mutableListOf<Frame>()
        for (i in 1..steps) {
            val p = progress * i / steps
            compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(x, 300f, p, edge)) }
            compose.mainClock.advanceTimeBy(16)
            frames += frame(i * 16L).also { record("drag %.2f".format(p), it) }
        }
        return frames
    }

    private fun release(): List<Frame> {
        compose.runOnUiThread { back.onBackPressed() }
        return run("released", 1200)
    }

    @Test
    fun `back from an account, its page is gone as the card lands`() {
        open(AccountRoute(1))
        assertTrue("the account's page shows", redness(frame(0).bottom) > 0.9f)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        val frames = run("back", 1200)
        save("card-back-button")
        assertTrue("the account's page still shows 400 ms after going back:\n${timeline.joinToString("\n")}", redness(frames.first { it.ms >= 400 }.bottom) < 0.1f)
    }

    @Test
    fun `the back gesture on an account's page fades it as it's dragged, then it's gone`() {
        open(AccountRoute(1))
        val dragged = drag(BackEventCompat.EDGE_LEFT, 0.6f)
        val released = release()
        save("card-back-gesture")
        val all = timeline.joinToString("\n")
        assertTrue("the card doesn't move as the gesture is dragged:\n$all", !isGreen(dragged.last().cardEnd))
        assertTrue("the account's page doesn't fade as the gesture is dragged:\n$all", redness(dragged.last().bottom) < 0.9f)
        assertTrue("the account's page still shows 400 ms after the gesture:\n$all", redness(released.first { it.ms >= 400 }.bottom) < 0.1f)
    }

    @Test
    fun `the back gesture moves a page the way the finger goes, a short way`() {
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            open(CardRoute)
            val dragged = drag(edge, 0.5f)
            val released = release()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val all = timeline.joinToString("\n")
            val last = dragged.last()
            // Uncovered on the side it moves away from, still covered on the side it moves to.
            val (uncovered, covered) = if (edge == BackEventCompat.EDGE_LEFT) last.left to last.right else last.right to last.left
            assertTrue("edge $edge: the page doesn't move off the side the finger leaves:\n$all", redness(uncovered) < redness(covered))
            assertTrue("edge $edge: the page went more than a short way:\n$all", redness(last.bottom) > 0.2f)
            assertTrue("edge $edge: the page still shows 500 ms after the gesture:\n$all", redness(released.first { it.ms >= 500 }.bottom) < 0.1f)
        }
        save("page-back-gesture")
    }

    @Test
    fun `between tabs, nothing slides`() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.navigate(ActivityRoute()) }
        val frames = run("tab", 600)
        save("tabs")
        // Left and right edge always the same colour: the pages fade, they don't slide.
        val all = timeline.joinToString("\n")
        assertTrue("a tab slid:\n$all", frames.all { it.left == it.right })
    }
}

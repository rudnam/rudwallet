package com.rudnam.wallet.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.component.EmptyState
import com.rudnam.wallet.core.designsystem.component.FieldLabel
import com.rudnam.wallet.core.designsystem.component.FigureSize
import com.rudnam.wallet.core.designsystem.component.IconTile
import com.rudnam.wallet.core.designsystem.component.Notice
import com.rudnam.wallet.core.designsystem.component.NoticeTone
import com.rudnam.wallet.core.designsystem.component.ProblemMessage
import com.rudnam.wallet.core.designsystem.component.SectionHeader
import com.rudnam.wallet.core.designsystem.component.StatFigure
import com.rudnam.wallet.core.designsystem.component.WalletListItem
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.WalletTopBar
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared components every page is built from, on one page (into build/outputs/roborazzi,
 * CI's native-screenshots), and what TalkBack hears from them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun page(dark: Boolean = false, onBack: () -> Unit = {}, onRetry: () -> Unit = {}) = compose.setContent {
        WalletTheme(darkTheme = dark) {
            val scroll = TopAppBarDefaults.pinnedScrollBehavior()
            Scaffold(topBar = { WalletTopBar(title = { Text("Components") }, scrollBehavior = scroll, onBack = onBack) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Money")
                    WalletPanel(Modifier.fillMaxWidth()) {
                        WalletListItem(headline = { Text("Accounts") }, leading = { IconTile(WalletIcons.budgets) }, trailing = { Text("¥12,340") })
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = WalletTheme.colors.border)
                        WalletListItem(
                            headline = { Text("Netflix") },
                            leading = { IconTile(WalletIcons.repeat) },
                            supporting = { Text("Paid Nov 3 · next Dec 3") },
                            trailing = { Text("¥1,490") },
                        )
                    }
                    WalletPanel(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatFigure("Paid this month", "¥8,200", Modifier.weight(1f))
                            StatFigure("Income", "¥320,000", Modifier.weight(1f), size = FigureSize.Medium, color = WalletTheme.colors.positive)
                        }
                    }
                    FieldLabel("How often")
                    Notice("Records from the card are added as to check.")
                    Notice("This looks like a duplicate.", tone = NoticeTone.Attention)
                    ProblemMessage("Can’t reach the server.", onRetry = onRetry)
                    EmptyState("Nothing recorded yet.")
                    Text("Body", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    @Test
    fun light() {
        page()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/components.png")
    }

    @Test
    fun dark() {
        page(dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/components-dark.png")
    }

    @Test
    fun `a section header is a heading, and back and try again do what they say`() {
        var backs = 0
        var retries = 0
        page(onBack = { backs++ }, onRetry = { retries++ })
        compose.onNodeWithText("Money").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, backs)
        assertEquals(1, retries)
    }

    @Test
    fun `a figure is read as one`() {
        page()
        compose.onNodeWithText("Paid this month", useUnmergedTree = false).assert(
            SemanticsMatcher("merged with its value") { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.contains("¥8,200") == true },
        )
    }
}

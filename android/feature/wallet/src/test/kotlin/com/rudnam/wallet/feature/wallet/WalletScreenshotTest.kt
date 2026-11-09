package com.rudnam.wallet.feature.wallet

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.TimeZone

/**
 * Wallet drawn on the JVM at phone size, light and dark, in English and
 * Japanese, into build/outputs/roborazzi. CI keeps them as the
 * native-screenshots artifact, to look at without a phone.
 *
 *   ./gradlew testDebugUnitTest -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WalletScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val navigation = object : WalletNavigation {
        override fun createAccount() {}
        override fun openPage(path: String) {}
        override fun openRecord(record: Transaction) {}
        override fun addRecord() {}
    }

    private fun ready(update: AppUpdate? = null, setup: SetupChecklist? = null, empty: Boolean = false) = WalletUiState.Ready(
        wallet = SampleWallet.wallet,
        accounts = SampleWallet.wallet.activeAccounts,
        month = YearMonth.of(2026, 11),
        spent = if (empty) 0 else 19_570,
        headline = if (empty) MonthHeadline.NothingSpent else monthHeadline(SampleWallet.records, SampleWallet.budgets, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 14)),
        latest = if (empty) emptyList() else SampleWallet.records.take(5),
        setup = setup,
        update = update,
        refreshing = false,
    )

    private fun shot(name: String, state: WalletUiState, dark: Boolean = false) {
        compose.setContent {
            WalletTheme(darkTheme = dark) {
                WalletScreen(state, navigation, onRefresh = {}, onDismissSetup = {}, onUpdateLater = {}, now = SampleWallet.now)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/wallet-$name.png")
    }

    @Test
    fun light() = shot("light", ready())

    @Test
    fun dark() = shot("dark", ready(), dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("ja", ready())

    /** The whole page, every card and record, on a very tall phone. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun whole() = shot("whole", ready())

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun newUser() = shot(
        "new",
        ready(update = AppUpdate(42, "2.1.42", ""), setup = SetupChecklist(accountsAdded = true, notificationsOn = false, budgetSet = false, noRecordsYet = true), empty = true),
    )

    @Test
    fun unavailable() = shot("unavailable", WalletUiState.Unavailable)
}

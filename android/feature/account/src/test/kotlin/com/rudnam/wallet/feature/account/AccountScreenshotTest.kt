package com.rudnam.wallet.feature.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth
import java.util.TimeZone

/** The account page at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val navigation = object : AccountNavigation {
        override fun back() {}
        override fun editAccounts() {}
        override fun openRecord(record: Transaction) {}
    }
    private val nothing = object : AccountActions {
        override fun count() {}
        override fun type(text: String) {}
        override fun save() {}
        override fun cancel() {}
    }

    private fun cashAccount() = AccountUiState.Ready(
        wallet = SampleWallet.wallet,
        account = SampleWallet.wallet.account(1)!!,
        month = YearMonth.of(2026, 11),
        out = 3_760,
        into = 0,
        records = SampleWallet.records.filter { it.accountId == 1L },
    )

    private fun shot(name: String, counting: AccountViewModel.CountState? = null, dark: Boolean = false) {
        compose.setContent { WalletTheme(darkTheme = dark) { AccountScreen(cashAccount(), counting, navigation, nothing, now = SampleWallet.now) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/account-$name.png")
    }

    @Test
    fun cash() = shot("cash")

    @Test
    fun dark() = shot("cash-dark", dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("cash-ja")
}

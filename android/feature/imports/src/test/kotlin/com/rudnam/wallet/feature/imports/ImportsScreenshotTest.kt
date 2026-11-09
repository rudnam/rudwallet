package com.rudnam.wallet.feature.imports

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.data.ic.IcCardTap
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.IcCardRead
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** The import pages at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ImportsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val navigation = object : ImportsNavigation {
        override fun back() {}
        override fun review(source: ImportSource, uri: String?) {}
        override fun card() {}
        override fun open(path: String) {}
    }
    private val review = object : ReviewActions {
        override fun setAccount(key: String, accountId: Long) {}
        override fun setNewName(key: String, name: String) {}
        override fun setCategory(groupKey: String, value: CategoryValue) {}
        override fun toggle(groupKey: String) {}
        override fun toggleAll() {}
        override fun setPicked(kind: String?, value: CategoryValue) {}
        override fun setMatchBalance(on: Boolean) {}
        override fun submit() {}
        override fun retry() {}
    }
    private val card = object : CardActions {
        override fun waitForCard(on: Boolean) {}
        override fun tapped(tap: IcCardTap) {}
        override fun importNew() {}
        override fun review() {}
    }

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { WalletTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/imports-$name.png")
    }

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun sources() = shot("sources") { ImportScreen(onPick = {}, navigation = navigation) }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun payPayReview() = shot("paypay") {
        val p = SampleImports.payPay
        val stage = ReviewStage.Ready(p, Choices.suggested(p), picked = setOf("expense:ラーメン太郎", "expense:ライフ"))
        ImportReviewScreen(ImportSource.PayPay, SampleWallet.wallet, stage, review, navigation)
    }

    @Test
    @Config(qualifiers = "ja-w411dp-h2000dp-xxhdpi")
    fun payPayReviewJapanese() = shot("paypay-ja") {
        val p = SampleImports.payPay
        ImportReviewScreen(ImportSource.PayPay, SampleWallet.wallet, ReviewStage.Ready(p, Choices.suggested(p)), review, navigation)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun cardReview() = shot("ic-review") {
        val p = SampleImports.icCard
        ImportReviewScreen(ImportSource.IcCard, SampleWallet.wallet, ReviewStage.Ready(p, Choices.suggested(p)), review, navigation)
    }

    @Test
    fun done() = shot("done") {
        val p = SampleImports.payPay
        ImportReviewScreen(ImportSource.PayPay, SampleWallet.wallet, ReviewStage.Done(p, ImportResult(13, 0, 1, 1, 1, 1, emptyList()), false), review, navigation)
    }

    /** A card with ¥3,520 on it, last used on 14 November (the block layout: internal/wallet/importic.go). */
    private val read = IcCardRead("0123456789ABCDEF", listOf("16010000356E00000000C00D00000000"))

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun cardPage() = shot("card") {
        val state = CardUiState(read = read, preview = SampleImports.icCard)
        CardScreen(SampleWallet.wallet, state, NfcState.Ready, card, onReview = {}, onTurnOnNfc = {}, navigation = navigation)
    }

    @Test
    fun cardWaiting() = shot("card-waiting", dark = true) {
        CardScreen(SampleWallet.wallet, CardUiState(waiting = true), NfcState.Ready, card, onReview = {}, onTurnOnNfc = {}, navigation = navigation)
    }
}

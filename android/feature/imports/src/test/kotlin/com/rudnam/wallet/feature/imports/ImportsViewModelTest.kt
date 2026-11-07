package com.rudnam.wallet.feature.imports

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.ic.IcCardProblem
import com.rudnam.wallet.core.data.ic.IcCardTap
import com.rudnam.wallet.core.data.ic.IcCards
import com.rudnam.wallet.core.data.repository.Fetched
import com.rudnam.wallet.core.model.AccountPick
import com.rudnam.wallet.core.model.CategoryPick
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestImportRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import com.rudnam.wallet.feature.imports.navigation.ImportReviewRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val imports = TestImportRepository().apply { preview = Fetched.Done(SampleImports.payPay) }
    private val cards = IcCards()

    private fun review(source: String = "paypay", uri: String? = "content://paypay.csv") =
        ImportReviewViewModel(SavedStateHandle(ImportReviewRoute(source, uri)), wallet, imports, cards)

    @Test
    fun `a file's preview starts from the server's suggestions`() = runTest {
        val vm = review()
        val ready = vm.stage.first { it is ReviewStage.Ready } as ReviewStage.Ready
        val c = ready.choices
        assertEquals(3L, c.accounts["PayPay残高"])
        assertEquals(AccountPick.NEW, c.accounts["PayPayカード"])
        assertEquals(AccountPick.SKIP, c.accounts["PayPayポイント"]) // left out last time
        assertEquals(CategoryValue.Existing(13), c.categories["expense:ドトール"])
        assertEquals(CategoryValue.New("Convenience stores"), c.categories["expense:ファミリーマート"])
        assertEquals(CategoryValue.None, c.categories["expense:ラーメン太郎"])
    }

    @Test
    fun `ticked shops are set together, only those of the category's kind`() = runTest {
        val vm = review()
        vm.stage.first { it is ReviewStage.Ready }
        vm.toggleAll()
        vm.setPicked("expense", CategoryValue.Existing(10))
        val c = (vm.stage.value as ReviewStage.Ready).choices
        assertEquals(CategoryValue.Existing(10), c.categories["expense:ラーメン太郎"])
        assertEquals(CategoryValue.Existing(10), c.categories["expense:ドトール"])
        assertEquals(CategoryValue.None, c.categories["income:PayPayポイント付与"]) // an income group keeps its own
        assertTrue((vm.stage.value as ReviewStage.Ready).picked.isEmpty())
    }

    @Test
    fun `importing sends the choices, a new account by the name typed`() = runTest {
        val vm = review()
        vm.stage.first { it is ReviewStage.Ready }
        vm.setNewName("PayPayカード", " My card ")
        vm.setAccount("PayPayポイント", 3)
        vm.setCategory("expense:ラーメン太郎", CategoryValue.Existing(10))
        vm.submit()
        val done = vm.stage.first { it is ReviewStage.Done } as ReviewStage.Done
        assertEquals(3, done.result.imported)
        val (source, choices) = imports.imported.single()
        assertEquals(ImportSource.PayPay, source)
        assertEquals(
            listOf(AccountPick("PayPay残高", 3), AccountPick("PayPayカード", AccountPick.NEW, "My card"), AccountPick("PayPayポイント", 3)),
            choices.accounts,
        )
        assertEquals(CategoryPick("ラーメン太郎", CategoryKind.Expense, 10, null), choices.categories[3])
        assertEquals(CategoryPick("ファミリーマート", CategoryKind.Expense, null, "Convenience stores"), choices.categories[1])
    }

    @Test
    fun `a file that can't be read, or the server offline, says so`() = runTest {
        assertEquals(ReviewStage.Unreadable, review(uri = "content://gone.csv").stage.first { it !is ReviewStage.Loading })
        imports.preview = Fetched.Offline
        assertEquals(ReviewStage.Failed("", offline = true), review().stage.first { it !is ReviewStage.Loading })
        imports.preview = Fetched.Done(SampleImports.payPay)
        imports.result = Fetched.Failed("line 9: no amount")
        val vm = review()
        vm.stage.first { it is ReviewStage.Ready }
        vm.submit()
        assertEquals("line 9: no amount", (vm.stage.first { (it as? ReviewStage.Ready)?.error != null } as ReviewStage.Ready).error)
    }

    @Test
    fun `a card imported before imports its new entries in one tap, with last time's accounts`() = runTest {
        imports.preview = Fetched.Done(SampleImports.icCard)
        val vm = CardViewModel(wallet, imports, cards)
        cards.tap(IcCardTap.Read(SampleImports.card))
        val state = vm.state.first { it.preview != null }
        assertTrue(state.known)
        imports.result = Fetched.Done(com.rudnam.wallet.core.model.ImportResult(2, 1, 0, 0, 0, 0, emptyList(), rows = SampleImports.icCard.rows))
        vm.importNew()
        val after = vm.state.first { it.result != null }
        val (_, choices) = imports.imported.single()
        assertEquals(listOf(AccountPick("card", 5), AccountPick("top-up", 1)), choices.accounts)
        assertFalse(choices.matchBalance)
        assertEquals(0, after.preview!!.new)
        assertTrue(after.preview!!.rows.none { it.status == ImportRowStatus.New })
        // The tapped card was taken: not shown again.
        assertEquals(null, cards.tapped.value)
    }

    @Test
    fun `a card that can't be read says why, and review hands the card over`() = runTest {
        imports.preview = Fetched.Done(SampleImports.icCard)
        val vm = CardViewModel(wallet, imports, cards)
        vm.waitForCard(true)
        vm.tapped(IcCardTap.Failed(IcCardProblem.Lost))
        assertEquals(IcCardProblem.Lost, vm.state.value.problem)
        assertFalse(vm.state.value.waiting)
        vm.tapped(IcCardTap.Read(SampleImports.card))
        vm.review()
        assertEquals(SampleImports.card, cards.review)
        val reviewed = review(source = "ic", uri = null).stage.first { it is ReviewStage.Ready } as ReviewStage.Ready
        assertEquals("CDEF", reviewed.preview.card)
        assertTrue(reviewed.choices.matchBalance)
    }
}

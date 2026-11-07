package com.rudnam.wallet.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.invoke
import com.rudnam.wallet.core.data.repository.ChangeResult
import com.rudnam.wallet.core.data.repository.RecordEvent
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.StatsResult
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.BudgetInput
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.CategoryTotal
import com.rudnam.wallet.core.model.DayTotal
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.testing.MainDispatcherRule
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.testing.TestInsightsRepository
import com.rudnam.wallet.core.testing.TestWalletRepository
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InsightsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val wallet = TestWalletRepository().apply { send(SampleWallet.wallet, SampleWallet.budgets, SampleWallet.records) }
    private val clock = Clock.fixed(SampleWallet.now, SampleWallet.zone)
    private val today = LocalDate.of(2026, 11, 14)
    private val events = RecordEvents()

    /** November so far: ¥19,570 spent; the months before ¥15,000 each, with a day of records. */
    private val insights = TestInsightsRepository(wallet).apply {
        answer = { q ->
            val november = q.from == LocalDate.of(2026, 11, 1).atStartOfDay(SampleWallet.zone).toInstant()
            val spent = if (november) 19_570L else 15_000L
            Stats(spent, 280_000, listOf(CategoryTotal(10, CategoryKind.Expense, spent, 3)), listOf(DayTotal(today, spent, 0)))
        }
    }

    private fun viewModel(route: InsightsRoute = InsightsRoute()) =
        InsightsViewModel(SavedStateHandle(route), wallet, insights, events, clock)

    private suspend fun InsightsViewModel.loaded() = uiState.first { it is InsightsUiState.Ready && it.stats != null } as InsightsUiState.Ready

    @Test
    fun `this month opens with its trend and where it's heading`() = runTest {
        val state = viewModel().loaded()
        assertEquals(Period.at(Span.Month, today), state.period)
        assertEquals(19_570L, state.stats!!.spent)
        val trend = state.trend as Trend.Compared
        assertEquals(Verdict.More, trend.verdict)
        assertEquals(30, trend.percent)
        assertNotNull(state.ahead)
        assertEquals(2, state.ahead!!.budgets.size)
        assertTrue(state.atLatest)
        // The month and the three before it, each up to the same day.
        assertTrue(insights.asked.size >= 4)
    }

    @Test
    fun `stepping, a week, a range and an account each ask for their own figures`() = runTest {
        val vm = viewModel()
        vm.loaded()
        vm.step(1) // nothing after this month yet
        assertEquals(Period.at(Span.Month, today), vm.loaded().period)
        vm.step(-1)
        val october = vm.uiState.first { (it as? InsightsUiState.Ready)?.period?.from == LocalDate.of(2026, 10, 1) } as InsightsUiState.Ready
        assertNull(october.ahead) // only the month still going looks ahead
        vm.setSpan(Span.Week)
        assertEquals(Period.at(Span.Week, LocalDate.of(2026, 10, 1)), (vm.uiState.first { (it as? InsightsUiState.Ready)?.period?.span == Span.Week } as InsightsUiState.Ready).period)
        vm.setRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10))
        vm.setAccount(3)
        val state = vm.uiState.first { (it as? InsightsUiState.Ready)?.accountId == 3L && it.stats != null } as InsightsUiState.Ready
        assertEquals(10, state.period.lengthInDays)
        assertEquals(3L, insights.asked.last().accountId)
    }

    @Test
    fun `a website path opens its period`() = runTest {
        val state = viewModel(InsightsRoute("year", "2025-03-01")).loaded()
        assertEquals(Period.at(Span.Year, LocalDate.of(2025, 1, 1)), state.period)
        assertNull(state.ahead)
    }

    @Test
    fun `offline, what was seen shows with a note, and a changed record asks again`() = runTest {
        val vm = viewModel()
        vm.loaded()
        insights.failure = StatsResult.Offline
        vm.refresh()
        val offline = vm.uiState.first { (it as? InsightsUiState.Ready)?.problem != null } as InsightsUiState.Ready
        assertEquals(InsightsProblem.Offline, offline.problem)
        assertEquals(19_570L, offline.stats!!.spent)

        insights.failure = null
        val before = insights.asked.size
        events.send(RecordEvent.Added(500))
        vm.uiState.first { (it as? InsightsUiState.Ready)?.problem == null }
        assertTrue(insights.asked.size > before)
    }

    @Test
    fun `budgets are made, changed and deleted, and a refusal says why`() = runTest {
        val vm = BudgetsViewModel(wallet, insights, clock)
        val ready = vm.uiState.first { it is BudgetsUiState.Ready } as BudgetsUiState.Ready
        // Eating out (Food & Drinks): ¥1,280 + ¥5,600 confirmed this month.
        assertEquals(6_880L, ready.budgets.first { it.budget.id == 2L }.spent)

        vm.create()
        vm.setAmount("abc")
        vm.save("All spending")
        assertTrue(vm.editor.value!!.invalidAmount)
        vm.setCategory(11)
        vm.setAmount("１２，０００")
        vm.save("Groceries")
        assertEquals(listOf(BudgetInput("Groceries", 11, 12_000)), insights.created)
        assertNull(vm.editor.value)

        vm.edit(Budget(2, "Eating out", 10, 8_000))
        assertEquals("8000", vm.editor.value!!.amount)
        insights.budgetResult = ChangeResult.Refused("choose a spending category")
        vm.save("Food & Drinks")
        assertEquals("choose a spending category", vm.editor.value!!.refused)

        insights.budgetResult = ChangeResult.Saved
        vm.askDelete(true)
        vm.delete()
        assertEquals(listOf(2L), insights.deleted)
        assertEquals(BudgetsMessage.Deleted("Eating out"), vm.messages.value.single())
    }
}

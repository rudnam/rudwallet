package com.rudnam.wallet.feature.insights

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.CategoryTotal
import com.rudnam.wallet.core.model.DayTotal
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.model.spentIn
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

/** Insights and the budgets at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class InsightsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val today = LocalDate.of(2026, 11, 14)

    private val navigation = object : InsightsNavigation {
        override fun openBudgets() {}
        override fun openRecords(month: YearMonth, categoryId: Long, accountId: Long?) {}
        override fun back() {}
    }
    private val insightsActions = object : InsightsActions {
        override fun setSpan(span: Span) {}
        override fun step(steps: Int) {}
        override fun setRange(first: LocalDate, last: LocalDate) {}
        override fun setAccount(accountId: Long?) {}
        override fun refresh() {}
    }
    private val budgetActions = object : BudgetsActions {
        override fun create() {}
        override fun edit(budget: com.rudnam.wallet.core.model.Budget) {}
        override fun setCategory(categoryId: Long?) {}
        override fun setAmount(text: String) {}
        override fun setName(text: String) {}
        override fun save(defaultName: String) {}
        override fun askDelete(asking: Boolean) {}
        override fun delete() {}
        override fun close() {}
    }

    /** November's sample records, as the server would total them, with a rent day far above the rest. */
    private val november = Stats(
        spent = 79_570,
        income = 280_000,
        categories = listOf(
            CategoryTotal(null, CategoryKind.Expense, 61_500, 2),
            CategoryTotal(11, CategoryKind.Expense, 7_630, 2),
            CategoryTotal(10, CategoryKind.Expense, 6_880, 2),
            CategoryTotal(12, CategoryKind.Expense, 2_100, 1),
            CategoryTotal(13, CategoryKind.Expense, 1_460, 2),
            CategoryTotal(14, CategoryKind.Income, 280_000, 1),
        ),
        days = listOf(
            DayTotal(LocalDate.of(2026, 11, 1), 60_000, 0),
            DayTotal(LocalDate.of(2026, 11, 2), 1_500, 0),
            DayTotal(LocalDate.of(2026, 11, 3), 4_210, 0),
            DayTotal(LocalDate.of(2026, 11, 5), 980, 0),
            DayTotal(LocalDate.of(2026, 11, 7), 5_600, 0),
            DayTotal(LocalDate.of(2026, 11, 10), 2_100, 280_000),
            DayTotal(LocalDate.of(2026, 11, 12), 3_420, 0),
            DayTotal(LocalDate.of(2026, 11, 13), 1_280, 0),
            DayTotal(LocalDate.of(2026, 11, 14), 480, 0),
        ),
    )
    private val before = Stats(
        spent = 70_000,
        income = 280_000,
        categories = listOf(CategoryTotal(null, CategoryKind.Expense, 60_000, 1), CategoryTotal(11, CategoryKind.Expense, 4_000, 2), CategoryTotal(10, CategoryKind.Expense, 2_000, 1)),
        days = listOf(DayTotal(LocalDate.of(2026, 10, 1), 60_000, 0)),
    )

    private fun thisMonth(): InsightsUiState.Ready {
        val period = Period.at(Span.Month, today)
        return InsightsUiState.Ready(
            wallet = SampleWallet.wallet,
            period = period,
            accountId = null,
            today = today,
            stats = november,
            trend = trend(november, listOf(before, before, before)),
            ahead = monthAhead(november, SampleWallet.budgets, period, today, SampleWallet.wallet),
            series = series(period, november),
            refreshing = false,
            problem = null,
        )
    }

    private fun shot(name: String, state: InsightsUiState.Ready = thisMonth(), dark: Boolean = false) {
        compose.setContent { WalletTheme(darkTheme = dark) { InsightsScreen(state, insightsActions, navigation) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/insights-$name.png")
    }

    @Test
    fun month() = shot("month")

    /** The whole page at once: the trends and the chart below the fold. */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun wholeMonth() = shot("month-whole")

    @Test
    fun dark() = shot("month-dark", dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("month-ja")

    @Test
    fun year() {
        val period = Period.at(Span.Year, today)
        shot("year", thisMonth().copy(period = period, ahead = null, series = series(period, november), trend = trend(november, listOf(before))))
    }

    private fun budgets(editor: BudgetEditor? = null, dark: Boolean = false, name: String) {
        val state = BudgetsUiState.Ready(SampleWallet.wallet, YearMonth.of(2026, 11), SampleWallet.budgets.map { BudgetRow(it, it.spentIn(SampleWallet.records)) })
        compose.setContent { WalletTheme(darkTheme = dark) { BudgetsScreen(state, editor, budgetActions, navigation) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/budgets-$name.png")
    }

    @Test
    fun budgetList() = budgets(name = "list")

    @Test
    fun budgetListDark() = budgets(dark = true, name = "list-dark")

    @Test
    fun noBudgets() {
        val state = BudgetsUiState.Ready(SampleWallet.wallet, YearMonth.of(2026, 11), emptyList())
        compose.setContent { WalletTheme { BudgetsScreen(state, null, budgetActions, navigation) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/budgets-empty.png")
    }
}

package com.rudnam.wallet.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rudnam.wallet.core.designsystem.component.PageLoading
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import com.rudnam.wallet.core.ui.serverText

/** Where Insights' and the budgets' taps lead. */
interface InsightsNavigation {
    fun openBudgets()

    /** Activity, for one category's records in [month]; [categoryId] [RecordFilter.NO_CATEGORY] is "no category". */
    fun openRecords(month: YearMonth, categoryId: Long, accountId: Long?)

    /** From the budgets, back to Insights. */
    fun back()
}

@Composable
internal fun InsightsDestination(navigation: InsightsNavigation, viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val s = state) {
        InsightsUiState.Loading -> PageLoading(Modifier.fillMaxSize())
        is InsightsUiState.Ready -> InsightsScreen(s, viewModel, navigation)
    }
}

/** Spending and income over a week, a month, a year or any range, compared with the periods before (docs/design, "insights"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(state: InsightsUiState.Ready, actions: InsightsActions, navigation: InsightsNavigation) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_insights_title)) },
                actions = { AccountChoice(state.wallet, state.accountId, actions::setAccount) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val stats = state.stats
            val noCategory = stringResource(R.string.feature_insights_no_category)
            val share = shareText()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "period") { PeriodControls(state, format, actions) }
                state.problem?.let { problem ->
                    item(key = "problem") { Problem(problem, hasFigures = stats != null) }
                }
                item(key = "spent") { Spent(state, format) }
                if (stats == null) {
                    if (state.problem == null) {
                        item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    }
                    return@LazyColumn
                }
                state.ahead?.let { ahead -> item(key = "ahead") { RestOfMonth(ahead, state, format, navigation) } }
                item(key = "went") { WhereItWent(stats, state, format, navigation) }
                state.trend?.let { trend -> item(key = "trends") { Trends(trend, state, format) } }
                state.series?.let { series -> item(key = "time") { OverTime(series, state, format) } }
                val incomes = bars(stats, CategoryKind.Income, state.wallet, noCategory)
                if (incomes.isNotEmpty()) {
                    item(key = "came") {
                        Section(stringResource(R.string.feature_insights_where_it_came_from)) {
                            CategoryBars(incomes, format::money, share = share)
                        }
                    }
                }
            }
        }
    }
}

/** Whose records count: every account's, or the one picked. */
@Composable
private fun AccountChoice(wallet: Wallet, accountId: Long?, onChoose: (Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chosen = wallet.account(accountId)
    Box(Modifier.padding(end = 8.dp)) {
        FilterChip(
            selected = chosen != null,
            onClick = { open = true },
            label = { Text(chosen?.name ?: stringResource(R.string.feature_insights_all_accounts), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 16.dp) },
            modifier = Modifier.widthIn(max = 180.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_insights_all_accounts)) },
                onClick = {
                    open = false
                    onChoose(null)
                },
            )
            for (a in wallet.activeAccounts) {
                DropdownMenuItem(
                    text = { Text(a.name) },
                    onClick = {
                        open = false
                        onChoose(a.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodControls(state: InsightsUiState.Ready, format: MoneyFormat, actions: InsightsActions) {
    val period = state.period
    val spans = listOf(
        Span.Week to R.string.feature_insights_week,
        Span.Month to R.string.feature_insights_month,
        Span.Year to R.string.feature_insights_year,
        Span.Range to R.string.feature_insights_range,
    )
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = stringResource(R.string.feature_insights_period)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
            spans.forEachIndexed { i, (span, name) ->
                SegmentedButton(
                    selected = period.span == span,
                    onClick = {
                        actions.setSpan(span)
                        if (span == Span.Range && period.span != Span.Range) picking = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, spans.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WalletTheme.colors.text,
                        activeContentColor = WalletTheme.colors.surface,
                        inactiveContainerColor = WalletTheme.colors.surface,
                    ),
                ) { Text(stringResource(name), maxLines = 1) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.step(-1) }) {
                LineIcon(WalletIcons.back, contentDescription = stringResource(previousLabel(period.span)))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val title = periodTitle(period, format)
                if (period.span == Span.Range) {
                    TextButton(onClick = { picking = true }) {
                        LineIcon(WalletIcons.calendar, contentDescription = null, size = 18.dp)
                        Box(Modifier.width(8.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, color = WalletTheme.colors.text)
                    }
                } else {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
            }
            IconButton(onClick = { actions.step(1) }, enabled = !state.atLatest) {
                LineIcon(WalletIcons.chevron, contentDescription = stringResource(nextLabel(period.span)))
            }
        }
    }
    if (picking) RangePicker(period, onDismiss = { picking = false }, onPick = { a, b -> picking = false; actions.setRange(a, b) })
}

/** Choosing a range of days; the picker works in UTC days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(period: Period, onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun day(m: Long) = Instant.ofEpochMilli(m).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = millis(period.from), initialSelectedEndDateMillis = millis(period.last))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(onClick = { start?.let { onPick(day(it), day(state.selectedEndDateMillis ?: it)) } }, enabled = start != null) {
                Text(stringResource(R.string.feature_insights_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_insights_cancel)) } },
    ) {
        DateRangePicker(
            state,
            title = { Text(stringResource(R.string.feature_insights_choose_range), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Problem(problem: InsightsProblem, hasFigures: Boolean) {
    val text = when (problem) {
        InsightsProblem.Offline -> stringResource(if (hasFigures) R.string.feature_insights_offline else R.string.feature_insights_offline_nothing)
        is InsightsProblem.Failed -> problem.message.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_insights_failed)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.attention)
}

/** What was spent, a day on average, and (when there was any) income and net. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Spent(state: InsightsUiState.Ready, format: MoneyFormat) {
    val s = state.stats
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.feature_insights_spent), style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted)
        Text(s?.let { format.money(it.spent) } ?: "…", style = MaterialTheme.typography.displaySmall.figures())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), modifier = Modifier.padding(top = 4.dp)) {
            Figure(stringResource(R.string.feature_insights_per_day), if (s != null && state.days > 0) format.money(Math.round(s.spent.toDouble() / state.days)) else "–")
            if (s != null && s.income > 0) {
                Figure(stringResource(R.string.feature_insights_income), format.money(s.income))
                Figure(stringResource(R.string.feature_insights_net), format.money(s.income - s.spent, signed = true))
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.bodySmall.figures(), fontWeight = FontWeight.SemiBold)
    }
}

/** A titled panel. */
@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun shareText(): (String, Int) -> String {
    val pattern = stringResource(R.string.feature_insights_share)
    return { what, percent -> String.format(pattern, what, percent) }
}

/** One bar per main category, its subcategories counted in; tapping one lists them all. */
private fun bars(stats: Stats, kind: CategoryKind, wallet: Wallet, noCategory: String): List<BarItem> =
    stats.byMainCategory(wallet).categories.filter { it.kind == kind }.map { t ->
        val c = wallet.category(t.categoryId)
        BarItem(t.categoryId, c, c?.name ?: noCategory, t.amount)
    }

@Composable
private fun WhereItWent(stats: Stats, state: InsightsUiState.Ready, format: MoneyFormat, navigation: InsightsNavigation) {
    val expenses = bars(stats, CategoryKind.Expense, state.wallet, stringResource(R.string.feature_insights_no_category))
    Section(stringResource(R.string.feature_insights_where_it_went)) {
        if (expenses.isEmpty()) {
            Text(stringResource(R.string.feature_insights_no_spending), color = WalletTheme.colors.muted)
        } else {
            // Records are listed a month at a time.
            val month = if (state.period.span == Span.Month) YearMonth.from(state.period.from) else null
            CategoryBars(
                expenses,
                format::money,
                share = shareText(),
                onSelect = month?.let { m -> { bar -> navigation.openRecords(m, bar.key ?: RecordFilter.NO_CATEGORY, state.accountId) } },
            )
        }
    }
}

/** The month still going: where it's heading, and what each budget allows a day. */
@Composable
private fun RestOfMonth(ahead: MonthAhead, state: InsightsUiState.Ready, format: MoneyFormat, navigation: InsightsNavigation) {
    val colors = WalletTheme.colors
    Section(
        stringResource(R.string.feature_insights_rest_of, format.monthName(YearMonth.from(state.period.from))),
        action = {
            TextButton(onClick = navigation::openBudgets) {
                Text(stringResource(if (ahead.budgets.isEmpty()) R.string.feature_insights_set_budget else R.string.feature_insights_edit_budgets))
            }
        },
    ) {
        Text(stringResource(R.string.feature_insights_heading, format.money(ahead.heading)), fontWeight = FontWeight.SemiBold)
        if (ahead.budgets.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.feature_insights_stay_within, ahead.daysLeft, ahead.daysLeft),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            for (pace in ahead.budgets) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BudgetMark(state.wallet.category(pace.budget.categoryId), pace.budget.categoryId == null)
                    Text(pace.budget.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(horizontalAlignment = Alignment.End) {
                        if (pace.over) {
                            Text(stringResource(R.string.feature_insights_over_by, format.money(pace.spent - pace.budget.amount)), color = colors.danger, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.figures())
                        } else {
                            Text(stringResource(R.string.feature_insights_up_to, format.money(pace.allowedPerDay)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.figures())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pace.tooFast) {
                                LineIcon(WalletIcons.warning, contentDescription = null, tint = colors.attention, size = 14.dp)
                                Box(Modifier.width(4.dp))
                            }
                            Text(stringResource(R.string.feature_insights_now_rate, format.money(pace.perDayNow)), style = MaterialTheme.typography.bodySmall.figures(), color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

/** A budget's mark: its category's, or for all spending the budgets glyph. */
@Composable
internal fun BudgetMark(category: com.rudnam.wallet.core.model.Category?, allSpending: Boolean, size: androidx.compose.ui.unit.Dp = 36.dp) {
    if (allSpending || category == null) {
        Box(Modifier.size(size).background(WalletTheme.colors.sunken, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            LineIcon(WalletIcons.budgets, contentDescription = null, tint = WalletTheme.colors.muted, size = 20.dp)
        }
    } else {
        CategoryIcon(category, Modifier.size(size))
    }
}

@Composable
private fun Trends(trend: Trend, state: InsightsUiState.Ready, format: MoneyFormat) {
    val colors = WalletTheme.colors
    val period = state.period
    Section(stringResource(R.string.feature_insights_trends)) {
        when (trend) {
            Trend.NotEnough -> Text(
                when (period.span) {
                    Span.Week -> stringResource(R.string.feature_insights_not_enough_week)
                    Span.Month -> stringResource(R.string.feature_insights_not_enough_month)
                    Span.Year -> stringResource(R.string.feature_insights_not_enough_year)
                    Span.Range -> pluralStringResource(R.plurals.feature_insights_not_enough_range, period.lengthInDays, period.lengthInDays)
                },
                color = colors.muted,
            )
            is Trend.Compared -> {
                // "by this point", put before or after the sentence as the language needs.
                val whenText = when {
                    !state.ongoing -> ""
                    period.span == Span.Year || period.span == Span.Range -> stringResource(R.string.feature_insights_by_day, state.days)
                    else -> stringResource(R.string.feature_insights_by_this_point)
                }
                val last = lastPeriods(period, trend.periods)
                val basis = stringResource(if (state.ongoing) R.string.feature_insights_average_same_point else R.string.feature_insights_average, last)
                val (mark, tint) = when (trend.verdict) {
                    Verdict.More -> WalletIcons.up to colors.danger
                    Verdict.Less -> WalletIcons.down to colors.positive
                    Verdict.Usual -> WalletIcons.level to colors.muted
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        LineIcon(mark, contentDescription = null, tint = tint, size = 20.dp)
                    }
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        val verdict = when (trend.verdict) {
                            Verdict.Usual -> stringResource(R.string.feature_insights_usual, whenText)
                            Verdict.More -> stringResource(R.string.feature_insights_more, trend.percent, whenText)
                            Verdict.Less -> stringResource(R.string.feature_insights_less, trend.percent, whenText)
                        }
                        Text(verdict.trim(), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.feature_insights_against, format.money(trend.spent), format.money(trend.usual), basis),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                }
                trend.shareOfIncome?.let { share ->
                    val text = when {
                        share <= 100 && whenText.isNotEmpty() -> stringResource(R.string.feature_insights_kept_so_far, "$share%", whenText, format.money(trend.income - trend.spent))
                        share <= 100 -> stringResource(R.string.feature_insights_kept, "$share%", format.money(trend.income - trend.spent))
                        whenText.isNotEmpty() -> stringResource(R.string.feature_insights_over_income_so_far, format.money(trend.spent - trend.income), whenText)
                        else -> stringResource(R.string.feature_insights_over_income, format.money(trend.spent - trend.income))
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                if (trend.higher.isNotEmpty()) Movers(stringResource(R.string.feature_insights_higher), trend.higher, state.wallet, format)
                if (trend.lower.isNotEmpty()) Movers(stringResource(R.string.feature_insights_lower), trend.lower, state.wallet, format)
            }
        }
    }
}

/** "the last 3 months", "the last week", "the last 2 30-day periods". */
@Composable
private fun lastPeriods(period: Period, n: Int): String = when (period.span) {
    Span.Week -> pluralStringResource(R.plurals.feature_insights_last_weeks, n, n)
    Span.Month -> pluralStringResource(R.plurals.feature_insights_last_months, n, n)
    Span.Year -> pluralStringResource(R.plurals.feature_insights_last_years, n, n)
    Span.Range -> if (n == 1) {
        stringResource(R.string.feature_insights_last_range, period.lengthInDays)
    } else {
        pluralStringResource(R.plurals.feature_insights_last_ranges, n, n, period.lengthInDays)
    }
}

@Composable
private fun Movers(title: String, items: List<Mover>, wallet: Wallet, format: MoneyFormat) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
        for (m in items) {
            val c = wallet.category(m.categoryId)
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryIcon(c, Modifier.size(32.dp), waiting = m.categoryId == null)
                Text(c?.name ?: stringResource(R.string.feature_insights_no_category), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (m.diff > 0) "+" else "−") + format.money(kotlin.math.abs(m.diff)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.figures())
                    Text(
                        stringResource(R.string.feature_insights_usually, format.money(m.amount), format.money(m.before)),
                        style = MaterialTheme.typography.bodySmall.figures(),
                        color = WalletTheme.colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverTime(series: Series, state: InsightsUiState.Ready, format: MoneyFormat) {
    val (label, hint) = when (series.step) {
        Step.Day -> R.string.feature_insights_per_day_chart to R.string.feature_insights_day_hint
        Step.Month -> R.string.feature_insights_per_month_chart to R.string.feature_insights_month_hint
        Step.Year -> R.string.feature_insights_per_year_chart to R.string.feature_insights_year_hint
    }
    val starts = series.starts
    val months = series.starts.size
    Section(stringResource(R.string.feature_insights_over_time)) {
        ColumnChart(
            values = series.values,
            labelFor = { i ->
                when (series.step) {
                    Step.Day -> format.date(starts[i], "EEEMMMd")
                    Step.Month -> format.date(starts[i], "yMMMM")
                    Step.Year -> format.date(starts[i], "y")
                }
            },
            tick = { i, slot ->
                val d = starts[i]
                when (series.step) {
                    Step.Day -> if (state.period.span == Span.Week) {
                        format.date(d, "EEE")
                    } else {
                        // Day numbers every 5 or 10 days, and where a range starts.
                        if (i == 0 || d.dayOfMonth % (if (slot < 14) 10 else 5) == 0) d.dayOfMonth.toString() else null
                    }
                    // A year's months by name; over longer ranges, where each year starts.
                    Step.Month -> if (months <= 12) format.date(d, if (slot >= 26) "MMM" else "MMMMM") else if (d.monthValue == 1 || i == 0) d.year.toString() else null
                    Step.Year -> if (slot >= 30 || i % 2 == 0) d.year.toString() else null
                }
            },
            money = format::money,
            compact = format::compact,
            description = stringResource(label),
            hint = stringResource(hint),
        )
    }
}

@Composable
private fun periodTitle(period: Period, format: MoneyFormat): String = when (period.span) {
    Span.Month -> format.date(period.from, "yMMMM")
    Span.Year -> format.date(period.from, "y")
    else -> format.dateRange(period.from, period.last)
}

private fun previousLabel(span: Span) = when (span) {
    Span.Week -> R.string.feature_insights_previous_week
    Span.Month -> R.string.feature_insights_previous_month
    Span.Year -> R.string.feature_insights_previous_year
    Span.Range -> R.string.feature_insights_previous_range
}

private fun nextLabel(span: Span) = when (span) {
    Span.Week -> R.string.feature_insights_next_week
    Span.Month -> R.string.feature_insights_next_month
    Span.Year -> R.string.feature_insights_next_year
    Span.Range -> R.string.feature_insights_next_range
}

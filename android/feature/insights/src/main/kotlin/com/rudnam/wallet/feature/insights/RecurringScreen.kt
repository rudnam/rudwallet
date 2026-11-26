package com.rudnam.wallet.feature.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.EmptyState
import com.rudnam.wallet.core.designsystem.component.FieldLabel
import com.rudnam.wallet.core.designsystem.component.PageLoading
import com.rudnam.wallet.core.designsystem.component.SectionHeader
import com.rudnam.wallet.core.designsystem.component.StatFigure
import com.rudnam.wallet.core.designsystem.component.WalletListItem
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.WalletTopBar
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.Recurring
import com.rudnam.wallet.core.model.RecurringKind
import com.rudnam.wallet.core.model.RecurringOverview
import com.rudnam.wallet.core.model.RecurringSuggestion
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.CategoryPickerExtra
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.rudnam.wallet.core.designsystem.R as DesignSystemR

@Composable
internal fun RecurringDestination(navigation: InsightsNavigation, snackbar: SnackbarHostState, viewModel: RecurringViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val haptics = rememberHaptics()

    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        val text = when (message) {
            RecurringMessage.Offline -> resources.getString(R.string.feature_insights_recurring_offline).also { haptics.error() }
            is RecurringMessage.Deleted -> resources.getString(R.string.feature_insights_deleted, message.name)
            is RecurringMessage.Added -> resources.getQuantityString(R.plurals.feature_insights_recurring_added, message.count, message.count).also { haptics.success() }
        }
        snackbar.showSnackbar(text)
    }

    RecurringScreen(state, editor, viewModel, navigation)
}

/** Kinds in the order they're listed, with their headings. */
private val kindHeadings = listOf(
    RecurringKind.Subscription to R.string.feature_insights_subscriptions,
    RecurringKind.Bill to R.string.feature_insights_bills,
    RecurringKind.Income to R.string.feature_insights_income,
)

/**
 * Recurring payments: what this month's have taken and will take, those
 * found in the records to add, and the ones added, by kind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurringScreen(state: RecurringUiState, editor: RecurringEditor?, actions: RecurringActions, navigation: InsightsNavigation) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = state as? RecurringUiState.Ready
    val overview = ready?.overview
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            WalletTopBar(
                title = { Text(stringResource(R.string.feature_insights_recurring)) },
                onBack = navigation::back,
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            // As the website's Add: whenever there's a list (the empty page has its own button).
            if (overview != null && (overview.recurring.isNotEmpty() || overview.suggestions.isNotEmpty())) {
                ExtendedFloatingActionButton(
                    onClick = actions::create,
                    icon = { LineIcon(WalletIcons.plus, contentDescription = null, stroke = 2.2f) },
                    text = { Text(stringResource(DesignSystemR.string.designsystem_add)) },
                    containerColor = WalletTheme.colors.action,
                    contentColor = WalletTheme.colors.onAction,
                    shape = RoundedCornerShape(18.dp),
                    elevation = FloatingActionButtonDefaults.loweredElevation(),
                )
            }
        },
    ) { padding ->
        if (ready == null || (overview == null && ready.problem == null)) {
            PageLoading(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val format = rememberMoneyFormat(ready.wallet.settings)
        PullToRefreshBox(isRefreshing = ready.refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ready.problem?.let { problem -> item(key = "problem") { Problem(problem, hasFigures = overview != null) } }
                if (overview == null) return@LazyColumn
                if (overview.recurring.isNotEmpty()) item(key = "month") { ThisMonth(overview, format) }
                if (overview.suggestions.isNotEmpty()) item(key = "found") { Suggestions(overview.suggestions, ready.wallet, format, actions) }
                if (overview.recurring.isEmpty() && overview.suggestions.isEmpty()) item(key = "none") { NoRecurring(actions) }
                for ((kind, heading) in kindHeadings) {
                    val items = overview.recurring.filter { it.active && it.kind == kind }
                    if (items.isNotEmpty()) item(key = "kind-${kind.apiName}") { Group(stringResource(heading), items, ready, format, actions) }
                }
                val stopped = overview.recurring.filterNot { it.active }
                if (stopped.isNotEmpty()) item(key = "stopped") { Group(stringResource(R.string.feature_insights_stopped), stopped, ready, format, actions) }
            }
        }
    }
    if (ready != null && editor != null) RecurringSheet(ready.wallet, editor, actions)
}

@Composable
private fun NoRecurring(actions: RecurringActions) {
    EmptyState(
        stringResource(R.string.feature_insights_no_recurring_detail),
        title = stringResource(R.string.feature_insights_no_recurring),
        icon = { LineIcon(WalletIcons.repeat, contentDescription = null, tint = WalletTheme.colors.muted, size = 40.dp) },
        action = {
            Button(
                onClick = actions::create,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) { Text(stringResource(R.string.feature_insights_add_recurring)) }
        },
    )
}

/** What this month's subscriptions and bills have taken, and what's still to come, each with its day. */
@Composable
private fun ThisMonth(overview: RecurringOverview, format: MoneyFormat) {
    val month = overview.month
    val colors = WalletTheme.colors
    Section(stringResource(R.string.feature_insights_this_month)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatFigure(stringResource(R.string.feature_insights_paid_this_month), format.money(month.paid), Modifier.weight(1f))
            StatFigure(stringResource(R.string.feature_insights_still_to_come), format.money(month.upcoming), Modifier.weight(1f))
        }
        for (due in month.due) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(due.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (due.late) stringResource(R.string.feature_insights_was_due, format.date(due.date, "MMMd")) else format.date(due.date, "MMMd"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (due.late) colors.danger else colors.muted,
                )
                Text(format.money(due.amount), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium.figures())
            }
        }
    }
}

@Composable
private fun kindName(kind: RecurringKind) = stringResource(
    when (kind) {
        RecurringKind.Subscription -> R.string.feature_insights_subscription
        RecurringKind.Bill -> R.string.feature_insights_bill
        RecurringKind.Income -> R.string.feature_insights_income
    },
)

@Composable
private fun everyName(months: Int) = when (months) {
    1 -> stringResource(R.string.feature_insights_every_month)
    12 -> stringResource(R.string.feature_insights_every_year)
    else -> stringResource(R.string.feature_insights_every_months, months)
}

/** Recurring payments found in the records: each to add with a tap, or to say it isn't one. */
@Composable
private fun Suggestions(suggestions: List<RecurringSuggestion>, wallet: Wallet, format: MoneyFormat, actions: RecurringActions) {
    Section(
        stringResource(R.string.feature_insights_found),
        action = if (suggestions.size > 1) {
            { TextButton(onClick = actions::addAll) { Text(stringResource(R.string.feature_insights_add_all)) } }
        } else {
            null
        },
    ) {
        Text(stringResource(R.string.feature_insights_found_detail), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        suggestions.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = WalletTheme.colors.border)
            val review = stringResource(R.string.feature_insights_review_named, s.input.name)
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = review) { actions.review(s) }.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(wallet.category(s.input.categoryId))
                    Column(Modifier.weight(1f)) {
                        Text(s.input.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${kindName(s.input.kind)} · ${everyName(s.input.months)} · " +
                                pluralStringResource(R.plurals.feature_insights_payments, s.payments.size, s.payments.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = WalletTheme.colors.muted,
                        )
                    }
                    Text(format.money(s.input.amount), style = MaterialTheme.typography.bodyMedium.figures())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.dismiss(s) }) { Text(stringResource(R.string.feature_insights_not_recurring), color = WalletTheme.colors.muted) }
                    val add = stringResource(R.string.feature_insights_add_named, s.input.name)
                    TextButton(onClick = { actions.add(s) }, modifier = Modifier.semantics { contentDescription = add }) {
                        Text(stringResource(DesignSystemR.string.designsystem_add))
                    }
                }
            }
        }
    }
}

/** Recurring payments of one kind (or those stopped): each with when it was paid and is next due. */
@Composable
private fun Group(title: String, items: List<Recurring>, state: RecurringUiState.Ready, format: MoneyFormat, actions: RecurringActions) {
    val active = items.first().active
    // What they come to in a month: a yearly one counts a twelfth.
    val perMonth = items.sumOf { it.estimate / it.input.months }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (active) Text(stringResource(R.string.feature_insights_per_month_about, format.money(perMonth)), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        }
        WalletPanel(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = WalletTheme.colors.border)
                RecurringRow(r, state, format) { actions.edit(r) }
            }
        }
    }
}

@Composable
private fun RecurringRow(r: Recurring, state: RecurringUiState.Ready, format: MoneyFormat, onClick: () -> Unit) {
    val colors = WalletTheme.colors
    val edit = stringResource(R.string.feature_insights_edit_named, r.name)
    val line = status(r, format)
    WalletListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = edit, onClick = onClick),
        leading = { CategoryIcon(state.wallet.category(r.input.categoryId)) },
        headline = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supporting = if (line.isEmpty()) null else {
            { Text(line, color = if (r.active && r.late) colors.danger else colors.muted) }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                val approx = if (r.kind == RecurringKind.Bill && r.payments.size > 1) "≈ " else ""
                Text(approx + format.money(r.estimate))
                Text(everyName(r.input.months), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        },
    )
}

/** When it was last paid and when it's next due, in a line. */
@Composable
private fun status(r: Recurring, format: MoneyFormat): String {
    val day = { d: LocalDate -> format.date(d, "MMMd") }
    val last = r.lastPaid
    val next = r.nextDue
    return when {
        !r.active -> last?.let { stringResource(R.string.feature_insights_last_paid, day(it.date)) } ?: stringResource(R.string.feature_insights_stopped)
        next == null -> ""
        r.late -> stringResource(R.string.feature_insights_was_due, day(next))
        last != null -> stringResource(R.string.feature_insights_paid_next, day(last.date), day(next))
        else -> stringResource(R.string.feature_insights_next, day(next))
    }
}

/** Adding or changing a recurring payment; one being changed can be stopped or deleted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecurringSheet(wallet: Wallet, editor: RecurringEditor, actions: RecurringActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val format = rememberMoneyFormat(wallet.settings)
    val colors = WalletTheme.colors
    ModalBottomSheet(onDismissRequest = actions::close, sheetState = sheet, containerColor = colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editor.id == null) R.string.feature_insights_new_recurring else R.string.feature_insights_edit_recurring),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            val kinds = RecurringKind.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, kind ->
                    SegmentedButton(
                        selected = editor.kind == kind,
                        onClick = { actions.setKind(kind) },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.text,
                            activeContentColor = colors.surface,
                            inactiveContainerColor = colors.surface,
                        ),
                    ) { Text(kindName(kind), maxLines = 1) }
                }
            }

            OutlinedTextField(
                value = editor.name,
                onValueChange = actions::setName,
                label = { Text(stringResource(R.string.feature_insights_name)) },
                singleLine = true,
                isError = editor.invalidName,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            val exponent = wallet.settings.currencyExponent
            OutlinedTextField(
                value = editor.amount,
                onValueChange = actions::setAmount,
                label = {
                    Text(
                        stringResource(
                            if (editor.kind == RecurringKind.Bill) R.string.feature_insights_about_how_much else R.string.feature_insights_amount,
                            wallet.settings.currency,
                        ),
                    )
                },
                prefix = { Text(format.symbol) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.figures(),
                isError = editor.invalidAmount,
                supportingText = if (editor.invalidAmount) {
                    { Text(stringResource(R.string.feature_insights_enter_amount)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = if (exponent == 0) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(stringResource(R.string.feature_insights_how_often))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in recurringMonths) {
                        FilterChip(selected = editor.months == m, onClick = { actions.setMonths(m) }, label = { Text(everyName(m)) })
                    }
                }
            }

            // When it's next due: Material's date picker.
            var picking by rememberSaveable { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = format.date(editor.startDate),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.feature_insights_next_due)) },
                    trailingIcon = { LineIcon(WalletIcons.calendar, contentDescription = null, size = 18.dp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable(role = Role.Button) { picking = true })
            }
            if (picking) DuePicker(editor.startDate, onDismiss = { picking = false }, onPick = { picking = false; actions.setStartDate(it) })

            // The category, of the kind's kind: none is a choice.
            var choosing by rememberSaveable { mutableStateOf(false) }
            val chosen = wallet.category(editor.categoryId)
            val none = stringResource(R.string.feature_insights_no_category)
            Box {
                OutlinedTextField(
                    value = wallet.categoryPath(chosen?.id) ?: none,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.feature_insights_category)) },
                    leadingIcon = chosen?.let { { CategoryIcon(it, Modifier.size(28.dp)) } },
                    trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 18.dp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable(role = Role.Button) { choosing = true })
            }
            if (choosing) {
                CategoryPickerSheet(
                    wallet = wallet,
                    kind = editor.kind.recordKind,
                    selectedId = chosen?.id,
                    onPick = { actions.setCategory(it.id) },
                    onDismiss = { choosing = false },
                    title = stringResource(R.string.feature_insights_category),
                    extras = listOf(CategoryPickerExtra(none, chosen == null) { actions.setCategory(null) }),
                )
            }

            AccountField(wallet, editor.accountId, actions::setAccount)

            OutlinedTextField(
                value = editor.matches,
                onValueChange = actions::setMatches,
                label = { Text(stringResource(R.string.feature_insights_matches)) },
                placeholder = { Text(editor.name) },
                supportingText = { Text(stringResource(R.string.feature_insights_matches_detail)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (editor.id != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.feature_insights_stopped_detail), Modifier.weight(1f))
                    Switch(checked = !editor.active, onCheckedChange = { actions.setActive(!it) })
                }
            }

            if (editor.payments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeader(stringResource(R.string.feature_insights_its_records))
                    for (p in editor.payments) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(format.date(p.date, "MMMd"), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(format.money(p.amount), style = MaterialTheme.typography.bodySmall.figures())
                        }
                    }
                }
            }

            editor.refused?.let { Text(serverText(it), color = colors.danger, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor.id != null) {
                    OutlinedButton(onClick = { actions.askDelete(true) }, enabled = !editor.busy) {
                        Text(stringResource(DesignSystemR.string.designsystem_delete), color = colors.danger)
                    }
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = actions::save,
                    enabled = !editor.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.action, contentColor = colors.onAction),
                ) {
                    if (editor.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onAction)
                    } else {
                        Text(stringResource(if (editor.id == null) DesignSystemR.string.designsystem_add else DesignSystemR.string.designsystem_save))
                    }
                }
            }
        }
    }
    if (editor.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { actions.askDelete(false) },
            text = { Text(stringResource(R.string.feature_insights_delete_recurring_question, editor.name)) },
            confirmButton = { TextButton(onClick = actions::delete) { Text(stringResource(DesignSystemR.string.designsystem_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { actions.askDelete(false) }) { Text(stringResource(DesignSystemR.string.designsystem_cancel)) } },
        )
    }
}

/** Which account it's paid from: any, or one of them. */
@Composable
private fun AccountField(wallet: Wallet, accountId: Long?, onChoose: (Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val any = stringResource(R.string.feature_insights_any_account)
    Box {
        OutlinedTextField(
            value = wallet.account(accountId)?.name ?: any,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.feature_insights_account)) },
            trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 18.dp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(any) }, onClick = { open = false; onChoose(null) })
            for (a in wallet.activeAccounts) {
                DropdownMenuItem(text = { Text(a.name) }, onClick = { open = false; onChoose(a.id) })
            }
        }
    }
}

/** Choosing the day it's next due; the picker works in UTC days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuePicker(date: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) {
                Text(stringResource(R.string.feature_insights_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignSystemR.string.designsystem_cancel)) } },
    ) { DatePicker(state) }
}

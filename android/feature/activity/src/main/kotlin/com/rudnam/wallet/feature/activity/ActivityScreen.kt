package com.rudnam.wallet.feature.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rudnam.wallet.core.designsystem.component.PageLoading
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.recordDays
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import java.time.Instant
import java.time.YearMonth
import com.rudnam.wallet.core.ui.ServerText

/** Where Activity's taps lead. */
interface ActivityNavigation {
    fun openRecord(record: Transaction)

    fun addRecord()
}

@Composable
internal fun ActivityDestination(navigation: ActivityNavigation, snackbar: SnackbarHostState, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val res = LocalResources.current

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh(userAsked = false)
        onPauseOrDispose {}
    }

    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        val text = when (message) {
            is ActivityMessage.ConfirmedOne -> res.getString(R.string.feature_activity_confirmed_payee, message.payee.ifEmpty { res.getString(R.string.feature_activity_payment) })
            is ActivityMessage.Confirmed -> res.getQuantityString(R.plurals.feature_activity_confirmed, message.count, message.count)
            is ActivityMessage.DeletedOne -> res.getString(R.string.feature_activity_deleted_payee, message.payee.ifEmpty { res.getString(R.string.feature_activity_payment) })
            is ActivityMessage.Restored ->
                if (message.count == 1) res.getString(R.string.feature_activity_restored_one) else res.getQuantityString(R.plurals.feature_activity_restored, message.count, message.count)
            ActivityMessage.Offline -> res.getString(R.string.feature_activity_offline)
            is ActivityMessage.Failed -> res.getString(R.string.feature_activity_failed, ServerText.translate(message.message, res.configuration.locales[0]))
            is ActivityMessage.Bulk -> {
                val n = message.changed
                val done = when (val c = message.change) {
                    is BulkChange.Delete -> res.getQuantityString(R.plurals.feature_activity_deleted, n, n)
                    is BulkChange.Move -> res.getQuantityString(R.plurals.feature_activity_moved, n, n)
                    is BulkChange.Categorize -> res.getQuantityString(R.plurals.feature_activity_recategorised, n, n)
                    is BulkChange.AddTag -> res.getQuantityString(R.plurals.feature_activity_tagged, n, n, c.tag)
                    is BulkChange.RemoveTag -> res.getQuantityString(R.plurals.feature_activity_untagged, n, n, c.tag)
                    is BulkChange.Confirm -> res.getQuantityString(R.plurals.feature_activity_confirmed, n, n)
                }
                if (message.skipped > 0) done + " " + res.getQuantityString(R.plurals.feature_activity_didnt_fit, message.skipped, message.skipped) else done
            }
        }
        // A delete can be undone: the server keeps deleted records a day.
        val undo: List<Long>? = when (message) {
            is ActivityMessage.DeletedOne -> listOf(message.id)
            is ActivityMessage.Bulk -> (message.change as? BulkChange.Delete)?.ids?.takeIf { message.changed > 0 }
            else -> null
        }
        val result = snackbar.showSnackbar(text, actionLabel = undo?.let { res.getString(R.string.feature_activity_undo) }, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed && undo != null) viewModel.restore(undo)
    }

    when (val s = state) {
        ActivityUiState.Loading -> PageLoading(Modifier.fillMaxSize())
        is ActivityUiState.Ready -> ActivityScreen(s, viewModel, navigation)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ActivityScreen(state: ActivityUiState.Ready, actions: ActivityActions, navigation: ActivityNavigation, now: Instant = Instant.now()) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val choosing = state.selected != null
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_activity_title)) },
                actions = {
                    if (!state.records.isNullOrEmpty()) {
                        TextButton(onClick = { actions.startChoosing() }) {
                            Text(stringResource(if (choosing) R.string.feature_activity_done else R.string.feature_activity_select))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
        floatingActionButton = {
            if (!choosing) {
                FloatingActionButton(
                    onClick = navigation::addRecord,
                    containerColor = WalletTheme.colors.action,
                    contentColor = WalletTheme.colors.onAction,
                    shape = RoundedCornerShape(18.dp),
                    elevation = FloatingActionButtonDefaults.loweredElevation(),
                ) { LineIcon(WalletIcons.plus, contentDescription = stringResource(R.string.feature_activity_add_record), stroke = 2.2f) }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { actions.refresh(userAsked = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "period", contentType = "period") { Period(state, format, actions) }
                item(key = "summary", contentType = "summary") { Summary(state, format) }
                item(key = "filters", contentType = "filters") { Filters(state, actions) }
                if (state.tags.isNotEmpty() || state.filter.tag.isNotEmpty()) {
                    item(key = "tags", contentType = "tags") { TagFilter(state, actions) }
                }
                if (state.toReview.isNotEmpty()) {
                    toCheck(state, format, actions, navigation, now)
                }
                if (choosing) {
                    stickyHeader(key = "bulk", contentType = "bulk") { BulkBar(state, actions) }
                }
                val records = state.records
                when {
                    state.searchOffline -> item(key = "offline") { Note(stringResource(R.string.feature_activity_search_offline)) }
                    records == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    records.isEmpty() -> item(key = "empty") { Note(stringResource(R.string.feature_activity_nothing)) }
                    else -> {
                        if (state.capped) item(key = "capped") { Note(stringResource(R.string.feature_activity_capped, "2,000")) }
                        recordDays(
                            records, state.wallet, format, navigation::openRecord, now,
                            selected = state.selected, onToggle = { actions.toggle(it.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 24.dp), color = WalletTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
}

/** The month, with arrows to the one before and after; or, searching, what's being searched and the way back. */
@Composable
private fun Period(state: ActivityUiState.Ready, format: MoneyFormat, actions: ActivityActions) {
    val f = state.filter
    if (f.everyMonth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (f.query.isNotBlank()) stringResource(R.string.feature_activity_every_month) else stringResource(R.string.feature_activity_tag_every_month, f.tag),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { actions.clearSearch() }) { Text(stringResource(R.string.feature_activity_back_to, monthTitle(state.month, format))) }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.previousMonth() }) {
            LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_activity_previous_month))
        }
        Text(monthTitle(state.month, format), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { actions.nextMonth() }, enabled = state.month < YearMonth.now()) {
            LineIcon(WalletIcons.chevron, contentDescription = stringResource(R.string.feature_activity_next_month))
        }
    }
}

private fun monthTitle(month: YearMonth, format: MoneyFormat): String =
    month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(format.locale, "yMMMM"), format.locale))

@Composable
private fun Summary(state: ActivityUiState.Ready, format: MoneyFormat) {
    val colors = WalletTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Figure(stringResource(R.string.feature_activity_spent), format.money(state.spent), colors.text)
        if (state.income > 0) {
            Figure(stringResource(R.string.feature_activity_income), format.money(state.income), colors.positive)
            Figure(stringResource(R.string.feature_activity_net), format.money(state.income - state.spent, signed = true), colors.text)
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.titleMedium.figures(), color = color)
    }
}

/** Account and category as chips with menus, and the search. */
@Composable
private fun Filters(state: ActivityUiState.Ready, actions: ActivityActions) {
    val f = state.filter
    var text by rememberSaveable(f.query) { mutableStateOf(f.query) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.feature_activity_search)) },
            leadingIcon = { LineIcon(WalletIcons.search, contentDescription = null, size = 20.dp) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        actions.search("")
                    }) { LineIcon(WalletIcons.close, contentDescription = stringResource(R.string.feature_activity_cancel), size = 18.dp) }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.search(text) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuChip(
                label = state.wallet.account(f.accountId)?.name ?: stringResource(R.string.feature_activity_account),
                selected = f.accountId != null,
                options = listOf(null to stringResource(R.string.feature_activity_all_accounts)) + state.wallet.accounts.map { it.id to it.name },
                onPick = { actions.setAccount(it) },
            )
            MenuChip(
                label = when (f.categoryId) {
                    null -> stringResource(R.string.feature_activity_category)
                    RecordFilter.NO_CATEGORY -> stringResource(R.string.feature_activity_no_category)
                    else -> state.wallet.categoryPath(f.categoryId) ?: stringResource(R.string.feature_activity_category)
                },
                selected = f.categoryId != null,
                options = listOf(null to stringResource(R.string.feature_activity_all_categories), RecordFilter.NO_CATEGORY to stringResource(R.string.feature_activity_no_category)) +
                    state.wallet.categoriesInOrder().map { it.id to (state.wallet.categoryPath(it.id) ?: it.name) },
                onPick = { actions.setCategory(it) },
            )
        }
    }
}

@Composable
private fun MenuChip(label: String, selected: Boolean, options: List<Pair<Long?, String>>, onPick: (Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 16.dp) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in options) {
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    onPick(id)
                })
            }
        }
    }
}

@Composable
private fun TagFilter(state: ActivityUiState.Ready, actions: ActivityActions) {
    val label = stringResource(R.string.feature_activity_filter_by_tag)
    LazyRow(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.tags, key = { it.name }) { tag ->
            val on = tag.name.equals(state.filter.tag, ignoreCase = true)
            FilterChip(
                selected = on,
                onClick = { actions.setTag(if (on) null else tag.name) },
                label = { Text("#${tag.name}  ${tag.count}") },
            )
        }
    }
}

/** What to do with the chosen records. */
@Composable
private fun BulkBar(state: ActivityUiState.Ready, actions: ActivityActions) {
    val chosen = state.selected.orEmpty()
    val all = state.records.orEmpty()
    val ids = chosen.toList()
    var deleting by rememberSaveable { mutableStateOf(false) }
    var tagging by rememberSaveable { mutableStateOf(false) }
    Surface(color = WalletTheme.colors.surface, shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chosen.isEmpty()) stringResource(R.string.feature_activity_choose_records) else pluralStringResource(R.plurals.feature_activity_chosen, chosen.size, chosen.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                val everything = chosen.size == all.size && all.isNotEmpty()
                TextButton(onClick = { actions.chooseAll(!everything) }) {
                    Text(if (everything) stringResource(R.string.feature_activity_choose_none) else stringResource(R.string.feature_activity_choose_all, all.size))
                }
            }
            if (chosen.isNotEmpty()) {
                HorizontalDivider(color = WalletTheme.colors.border)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val expense = state.wallet.categories.filter { it.kind == CategoryKind.Expense }
                    val income = state.wallet.categories.filter { it.kind == CategoryKind.Income }
                    MenuButton(stringResource(R.string.feature_activity_set_category), listOf(RecordFilter.NO_CATEGORY to stringResource(R.string.feature_activity_no_category)) + (expense + income).map { it.id to it.name }) {
                        actions.bulk(BulkChange.Categorize(ids, it.takeUnless { id -> id == RecordFilter.NO_CATEGORY }))
                    }
                    MenuButton(stringResource(R.string.feature_activity_move), state.wallet.activeAccounts.map { it.id to it.name }) {
                        actions.bulk(BulkChange.Move(ids, it))
                    }
                    TextButton(onClick = { tagging = true }, enabled = !state.busy) { Text(stringResource(R.string.feature_activity_tag)) }
                    TextButton(onClick = { deleting = true }, enabled = !state.busy) {
                        Text(stringResource(R.string.feature_activity_delete), color = WalletTheme.colors.danger)
                    }
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(pluralStringResource(R.plurals.feature_activity_delete_ask, chosen.size, chosen.size)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    actions.bulk(BulkChange.Delete(ids))
                }) { Text(stringResource(R.string.feature_activity_delete), color = WalletTheme.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.feature_activity_cancel)) } },
        )
    }
    if (tagging) {
        TagDialog(state, chosen, onDismiss = { tagging = false }) { change ->
            tagging = false
            actions.bulk(change)
        }
    }
}

@Composable
private fun MenuButton(label: String, options: List<Pair<Long, String>>, onPick: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in options) DropdownMenuItem(text = { Text(name) }, onClick = {
                open = false
                onPick(id)
            })
        }
    }
}

/** Tagging the chosen records: a new tag or one in use, or taking one of theirs off. */
@Composable
private fun TagDialog(state: ActivityUiState.Ready, chosen: Set<Long>, onDismiss: () -> Unit, onChange: (BulkChange) -> Unit) {
    val ids = chosen.toList()
    var text by rememberSaveable { mutableStateOf("") }
    val theirs = state.records.orEmpty().filter { it.id in chosen }.flatMap { it.tags }.distinctBy { it.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_activity_tag_with)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.feature_activity_new_tag)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { cleanTag(text)?.let { onChange(BulkChange.AddTag(ids, it)) } }),
                )
                if (state.tags.isNotEmpty()) {
                    Text(stringResource(R.string.feature_activity_add_tag), style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.tags, key = { it.name }) { t ->
                            FilterChip(selected = false, onClick = { onChange(BulkChange.AddTag(ids, t.name)) }, label = { Text("#${t.name}") })
                        }
                    }
                }
                if (theirs.isNotEmpty()) {
                    Text(stringResource(R.string.feature_activity_remove_tag), style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(theirs, key = { it }) { t ->
                            FilterChip(selected = true, onClick = { onChange(BulkChange.RemoveTag(ids, t)) }, label = { Text("#$t") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { cleanTag(text)?.let { onChange(BulkChange.AddTag(ids, it)) } }, enabled = cleanTag(text) != null) {
                Text(stringResource(R.string.feature_activity_add_tag))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_activity_cancel)) } },
    )
}

/** A tag as typed, tidied: no "#" in front, single spaces, at most 30 characters. */
internal fun cleanTag(text: String): String? =
    text.trim().trimStart('#', '＃').trim().replace(Regex("\\s+"), " ").take(30).ifEmpty { null }

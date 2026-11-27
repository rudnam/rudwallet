package com.rudnam.wallet.feature.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.Notice
import com.rudnam.wallet.core.designsystem.component.NoticeTone
import com.rudnam.wallet.core.designsystem.component.ProblemMessage
import com.rudnam.wallet.core.designsystem.component.SectionHeader
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.AccountPick
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.ImportAccountGroup
import com.rudnam.wallet.core.model.ImportBalance
import com.rudnam.wallet.core.model.ImportCategoryGroup
import com.rudnam.wallet.core.model.ImportPreview
import com.rudnam.wallet.core.model.ImportResult
import com.rudnam.wallet.core.model.ImportRow
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.CategoryPickerExtra
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText
import com.rudnam.wallet.core.designsystem.R as DesignSystemR

@Composable
internal fun ImportReviewDestination(navigation: ImportsNavigation, @Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState, viewModel: ImportReviewViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    ImportReviewScreen(viewModel.source, wallet, stage, viewModel, navigation)
}

/** The source's name, as the page's title. */
@Composable
private fun sourceName(source: ImportSource) = when (source) {
    ImportSource.PayPay -> "PayPay"
    ImportSource.Mufg -> "MUFG"
    ImportSource.BudgetBakers -> "BudgetBakers"
    ImportSource.IcCard -> stringResource(R.string.feature_imports_ic_card)
    ImportSource.Vpass -> "Vpass"
}

/**
 * An import before anything is recorded: where each group of records goes
 * (an account each way of paying, role or account in the file; a category
 * each shop or category), how each row would be recorded, and the balance
 * against the statement or card. Then what the import did.
 */
@Composable
internal fun ImportReviewScreen(source: ImportSource, wallet: Wallet?, stage: ReviewStage, actions: ReviewActions, navigation: ImportsNavigation) {
    // Which groups of rows are open: those to add are, those already in RudWallet aren't.
    var open by rememberSaveable { mutableStateOf(listOf(ImportRowStatus.New.name)) }
    val toggle = { status: ImportRowStatus -> open = if (status.name in open) open - status.name else open + status.name }
    val bar: @Composable () -> Unit = {
        if (wallet != null && stage is ReviewStage.Ready && stage.preview.records > 0) ImportBar(stage, actions, navigation)
    }
    ImportPage(sourceName(source), navigation, bottomBar = bar) {
        if (wallet == null) {
            item { Loading() }
            return@ImportPage
        }
        when (stage) {
            ReviewStage.Loading -> item {
                Loading(stringResource(if (source == ImportSource.IcCard) R.string.feature_imports_reading_card else R.string.feature_imports_reading_file))
            }
            ReviewStage.Unreadable -> item { ProblemMessage(stringResource(R.string.feature_imports_unreadable)) }
            is ReviewStage.Failed -> item {
                ProblemMessage(
                    if (stage.offline) stringResource(R.string.feature_imports_offline) else stage.message.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_imports_failed),
                    onRetry = actions::retry,
                )
            }
            is ReviewStage.Ready -> review(stage, wallet, actions, navigation, open.mapNotNull { n -> ImportRowStatus.entries.firstOrNull { it.name == n } }.toSet(), toggle)
            is ReviewStage.Done -> done(stage, wallet, actions, navigation)
        }
    }
}

@Composable
private fun Loading(text: String? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        text?.let { Text(it, color = WalletTheme.colors.muted) }
    }
}

private fun LazyListScope.review(
    stage: ReviewStage.Ready,
    wallet: Wallet,
    actions: ReviewActions,
    navigation: ImportsNavigation,
    open: Set<ImportRowStatus>,
    toggle: (ImportRowStatus) -> Unit,
) {
    val p = stage.preview
    val c = stage.choices
    if (p.records == 0) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (p.source == ImportSource.PayPay || p.source == ImportSource.Vpass) R.string.feature_imports_no_payments else R.string.feature_imports_no_records))
                OutlinedButton(onClick = navigation::back) { Text(stringResource(R.string.feature_imports_choose_another)) }
            }
        }
        return
    }
    item(key = "summary") { Summary(p, wallet, stage.checking) }
    if (p.gap) item { Notice(stringResource(R.string.feature_imports_gap_review), tone = NoticeTone.Attention) }
    item { SectionHeader(stringResource(R.string.feature_imports_where_they_go), Modifier.padding(top = 8.dp)) }
    item(key = "accounts") {
        WalletPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Intro(p, wallet)
                for (g in p.accounts) AccountChoice(p.source, g, wallet, c, actions)
            }
        }
    }
    if (p.categories.isNotEmpty()) {
        item {
            Text(
                stringResource(
                    when (p.source) {
                        ImportSource.PayPay, ImportSource.Vpass -> R.string.feature_imports_paypay_categories
                        ImportSource.Mufg -> R.string.feature_imports_mufg_categories
                        ImportSource.BudgetBakers -> R.string.feature_imports_budgetbakers_categories
                        ImportSource.IcCard -> R.string.feature_imports_ic_categories
                    },
                ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item(key = "categories") { CategoryGroups(p, wallet, stage, actions) }
    }
    if (p.problems.isNotEmpty()) {
        item { Notice(stringResource(R.string.feature_imports_problems) + "\n" + p.problems.joinToString("\n") { "• $it" }, tone = NoticeTone.Attention) }
    }
    if (p.rows.isNotEmpty()) {
        item {
            SectionHeader(
                stringResource(
                    when (p.source) {
                        ImportSource.IcCard -> R.string.feature_imports_card_entries
                        ImportSource.Mufg -> R.string.feature_imports_statement_rows
                        else -> R.string.feature_imports_file_rows
                    },
                ),
                Modifier.padding(top = 8.dp),
            )
        }
        // A card's entries come oldest first; the newest shows first, as on the card page. A statement stays in its order.
        val all = if (p.source == ImportSource.IcCard) p.rows.asReversed() else p.rows
        for ((status, rows) in rowGroups(all)) {
            item(key = "group-${status.name}") { GroupHeader(status, rows.size, status in open) { toggle(status) } }
            if (status in open) items(rows.size, key = { "row-${status.name}-$it" }) { i -> RowLine(rows[i], p, wallet, c) }
        }
    }
    p.balance?.takeIf { it.accountId != null || p.source == ImportSource.IcCard }?.let { b ->
        item { BalanceNote(p.source, b, wallet, c.matchBalance, done = false, newName = c.newNames[p.accounts.firstOrNull()?.key].orEmpty()) }
        item {
            Row(
                Modifier.fillMaxWidth().toggleable(value = c.matchBalance, role = Role.Switch, onValueChange = actions::setMatchBalance).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(if (p.source == ImportSource.IcCard) R.string.feature_imports_match_card else R.string.feature_imports_match_bank), Modifier.weight(1f))
                Switch(checked = c.matchBalance, onCheckedChange = null)
            }
        }
    }
    stage.error?.let { item { ProblemMessage(it.ifEmpty { null }?.let { m -> serverText(m) } ?: stringResource(R.string.feature_imports_offline)) } }
}

/** Rows by what becomes of them: to add first, then those already there, then the rest. */
private fun rowGroups(rows: List<ImportRow>): List<Pair<ImportRowStatus, List<ImportRow>>> {
    val order = listOf(ImportRowStatus.New, ImportRowStatus.Imported, ImportRowStatus.Matched, ImportRowStatus.Skipped, ImportRowStatus.None)
    val group = { r: ImportRow -> if (r.status == ImportRowStatus.Start) ImportRowStatus.None else r.status }
    return order.mapNotNull { s -> rows.filter { group(it) == s }.takeIf { it.isNotEmpty() }?.let { s to it } }
}

@Composable
private fun GroupHeader(status: ImportRowStatus, count: Int, open: Boolean, onClick: () -> Unit) {
    val title = stringResource(
        when (status) {
            ImportRowStatus.New -> R.string.feature_imports_group_new
            ImportRowStatus.Imported -> R.string.feature_imports_group_imported
            ImportRowStatus.Matched -> R.string.feature_imports_group_matched
            ImportRowStatus.Skipped -> R.string.feature_imports_group_skipped
            else -> R.string.feature_imports_group_other
        },
        count,
    )
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(statusColor(status), RoundedCornerShape(4.dp)))
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (open) R.string.feature_imports_hide else R.string.feature_imports_show),
            style = MaterialTheme.typography.labelMedium,
            color = WalletTheme.colors.muted,
        )
    }
}

@Composable
private fun statusColor(status: ImportRowStatus) = when (status) {
    ImportRowStatus.New -> WalletTheme.colors.action
    ImportRowStatus.Imported, ImportRowStatus.Matched -> WalletTheme.colors.positive
    else -> WalletTheme.colors.muted
}

/**
 * What importing does, before anything else: how many records it adds, and
 * how many it leaves out because they're already there. The server works
 * these out by running the import and rolling it back.
 */
@Composable
private fun Summary(p: ImportPreview, wallet: Wallet, checking: Boolean) {
    val format = rememberMoneyFormat(wallet.settings)
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (p.new > 0) pluralStringResource(R.plurals.feature_imports_to_add, p.new, p.new) else stringResource(R.string.feature_imports_nothing_new),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (checking) {
                    val checkingText = stringResource(R.string.feature_imports_rechecking)
                    CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = checkingText }, strokeWidth = 2.dp)
                }
            }
            if (p.from != null && p.to != null) {
                Text(format.dateRange(p.from!!, p.to!!) + (p.card?.let { " · ••$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            }
            val lines = buildList {
                if (p.duplicates > 0) add(ImportRowStatus.Imported to pluralStringResource(R.plurals.feature_imports_summary_imported, p.duplicates, p.duplicates))
                if (p.matched > 0) add(ImportRowStatus.Matched to pluralStringResource(R.plurals.feature_imports_summary_matched, p.matched, p.matched))
                if (p.skipped > 0) add(ImportRowStatus.Skipped to pluralStringResource(R.plurals.feature_imports_summary_skipped, p.skipped, p.skipped))
            }
            for ((status, text) in lines) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    LineIcon(if (status == ImportRowStatus.Skipped) WalletIcons.close else WalletIcons.check, contentDescription = null, tint = statusColor(status), size = 18.dp)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.feature_imports_summary_safe), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        }
    }
}

@Composable
private fun Intro(p: ImportPreview, wallet: Wallet) {
    val format = rememberMoneyFormat(wallet.settings)
    val from = p.from?.let { format.date(it) }.orEmpty()
    val to = p.to?.let { format.date(it) }.orEmpty()
    val text = when (p.source) {
        ImportSource.PayPay, ImportSource.Vpass -> pluralStringResource(R.plurals.feature_imports_payments_range, p.records, p.records, from, to)
        ImportSource.Mufg -> pluralStringResource(R.plurals.feature_imports_rows_range, p.records, p.records, from, to)
        ImportSource.BudgetBakers -> pluralStringResource(R.plurals.feature_imports_records_range, p.records, p.records, from, to)
        ImportSource.IcCard -> pluralStringResource(R.plurals.feature_imports_entries_range, p.records, p.records, from, to, p.card.orEmpty())
    }
    Text(text, modifier = Modifier.padding(horizontal = 4.dp))
}

/** What a group is: a role's description, or its name in the file. */
@Composable
private fun groupLabel(source: ImportSource, key: String): String = when (source) {
    ImportSource.Mufg -> when (key) {
        "bank" -> stringResource(R.string.feature_imports_role_bank)
        "cash" -> stringResource(R.string.feature_imports_role_cash)
        "paypay-card" -> stringResource(R.string.feature_imports_role_paypay_card)
        "smbc-card" -> stringResource(R.string.feature_imports_role_smbc_card)
        "paypay" -> stringResource(R.string.feature_imports_role_paypay)
        else -> key
    }
    ImportSource.IcCard -> if (key == "top-up") stringResource(R.string.feature_imports_role_top_up) else stringResource(R.string.feature_imports_role_card)
    else -> key
}

/** The account a group's records go to: one of the user's, a new one (named here), or (PayPay) none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountChoice(source: ImportSource, group: ImportAccountGroup, wallet: Wallet, choices: Choices, actions: ReviewActions) {
    val chosen = choices.accounts[group.key] ?: AccountPick.NEW
    val newName = choices.newNames[group.key].orEmpty()
    val accounts = wallet.accounts.filter { !it.archived || it.id == chosen }
    val newLabel = if (newName.isBlank()) stringResource(R.string.feature_imports_new_account) else stringResource(R.string.feature_imports_new_account_named, newName.trim())
    val skipLabel = stringResource(R.string.feature_imports_dont_import)
    val label = when (chosen) {
        AccountPick.NEW -> newLabel
        AccountPick.SKIP -> skipLabel
        else -> wallet.account(chosen)?.name.orEmpty()
    }
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            groupLabel(source, group.key) + " · " + pluralStringResource(R.plurals.feature_imports_record_count, group.count, group.count),
            style = MaterialTheme.typography.labelLarge,
        )
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (a in accounts) {
                    DropdownMenuItem(text = { Text(a.name) }, onClick = {
                        open = false
                        actions.setAccount(group.key, a.id)
                    })
                }
                DropdownMenuItem(text = { Text(newLabel) }, onClick = {
                    open = false
                    actions.setAccount(group.key, AccountPick.NEW)
                })
                if (source == ImportSource.PayPay) {
                    DropdownMenuItem(text = { Text(skipLabel) }, onClick = {
                        open = false
                        actions.setAccount(group.key, AccountPick.SKIP)
                    })
                }
            }
        }
        if (chosen == AccountPick.NEW) {
            OutlinedTextField(
                value = newName,
                onValueChange = { actions.setNewName(group.key, it) },
                label = { Text(stringResource(R.string.feature_imports_new_account_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            accounts.firstOrNull { it.name.equals(newName.trim(), ignoreCase = true) && newName.isNotBlank() }?.let {
                Text(stringResource(R.string.feature_imports_existing_account, it.name), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            }
        }
        if (group.candidates.isNotEmpty()) {
            Text(
                stringResource(R.string.feature_imports_also_looks, group.candidates.mapNotNull { wallet.account(it)?.name }.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = WalletTheme.colors.muted,
            )
        }
    }
}

/** How many groups show before "Show all". */
private const val FIRST_GROUPS = 12

/** Fewer groups than this are quicker to set one by one, without ticking. */
private const val BULK_GROUPS = 4

/** Each category group with its category; ticking several sets them at once. */
@Composable
private fun CategoryGroups(p: ImportPreview, wallet: Wallet, stage: ReviewStage.Ready, actions: ReviewActions) {
    val shops = p.source != ImportSource.BudgetBakers
    var showAll by rememberSaveable { mutableStateOf(p.categories.size <= FIRST_GROUPS + 3) }
    val shown = if (showAll) p.categories else p.categories.take(FIRST_GROUPS)
    val bulk = p.categories.size >= BULK_GROUPS
    WalletPanel(Modifier.fillMaxWidth()) {
        if (bulk) BulkBar(p, wallet, stage, actions, shops)
        shown.forEachIndexed { i, g ->
            if (i > 0 || bulk) HorizontalDivider(color = WalletTheme.colors.border)
            GroupRow(g, wallet, p.newCategories, stage, actions, bulk)
        }
        if (!showAll) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(8.dp)) {
                Text(
                    if (shops) {
                        pluralStringResource(R.plurals.feature_imports_show_all_shops, p.categories.size, p.categories.size)
                    } else {
                        pluralStringResource(R.plurals.feature_imports_show_all_categories, p.categories.size, p.categories.size)
                    },
                )
            }
        }
    }
}

@Composable
private fun BulkBar(p: ImportPreview, wallet: Wallet, stage: ReviewStage.Ready, actions: ReviewActions, shops: Boolean) {
    val picked = p.categories.filter { it.key in stage.picked }
    val state = when {
        picked.isEmpty() -> ToggleableState.Off
        picked.size == p.categories.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(state = state, onClick = actions::toggleAll)
        Text(
            if (picked.isEmpty()) {
                stringResource(if (shops) R.string.feature_imports_tick_shops else R.string.feature_imports_tick_categories)
            } else {
                pluralStringResource(R.plurals.feature_imports_chosen, picked.size, picked.size)
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (picked.isEmpty()) WalletTheme.colors.muted else WalletTheme.colors.text,
        )
        if (picked.isNotEmpty()) {
            val kinds = picked.map { it.kind }.distinct()
            CategoryMenu(
                kinds = kinds,
                wallet = wallet,
                newCategories = p.newCategories,
                extra = null,
                onChoose = { kind, value -> actions.setPicked(kind?.apiName, value) },
            ) { open -> TextButton(onClick = open) { Text(stringResource(R.string.feature_imports_set_category)) } }
        }
    }
}

@Composable
private fun GroupRow(g: ImportCategoryGroup, wallet: Wallet, newCategories: List<Category>, stage: ReviewStage.Ready, actions: ReviewActions, bulk: Boolean) {
    val value = stage.choices.categories[g.key] ?: CategoryValue.None
    val format = rememberMoneyFormat(wallet.settings)
    val shown: Category? = when (value) {
        is CategoryValue.Existing -> wallet.category(value.id)
        is CategoryValue.New -> newCategories.firstOrNull { it.name == value.name } ?: Category(0, value.name, g.kind, "", "")
        CategoryValue.None -> null
    }
    val choose = stringResource(R.string.feature_imports_choose_named, g.name)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bulk) {
            Checkbox(checked = g.key in stage.picked, onCheckedChange = { actions.toggle(g.key) }, modifier = Modifier.semantics { contentDescription = choose })
        } else {
            Box(Modifier.size(12.dp))
        }
        CategoryIcon(shown, Modifier.size(32.dp), waiting = shown == null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (g.kind == CategoryKind.Income) {
                    Text(
                        stringResource(R.string.feature_imports_income_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletTheme.colors.muted,
                        modifier = Modifier.background(WalletTheme.colors.sunken, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val detail = buildList {
                add(pluralStringResource(R.plurals.feature_imports_record_count, g.count, g.count) + " · " + format.money(g.total))
                if (g.learned && value == CategoryValue.Existing(g.categoryId ?: -1)) add(stringResource(R.string.feature_imports_as_before))
                if (value is CategoryValue.New) add(stringResource(R.string.feature_imports_new_category))
            }.joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.bodySmall.figures(), color = WalletTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CategoryMenu(
            kinds = listOf(g.kind),
            wallet = wallet,
            newCategories = newCategories,
            extra = g.newName.ifEmpty { null },
            onChoose = { _, v -> actions.setCategory(g.key, v) },
        ) { open ->
            TextButton(onClick = open, modifier = Modifier.widthIn(max = 140.dp).semantics { contentDescription = "${g.name}: ${shown?.name.orEmpty()}" }) {
                Text(shown?.name ?: stringResource(R.string.feature_imports_no_category), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (shown == null) WalletTheme.colors.muted else WalletTheme.colors.text)
            }
        }
    }
}

/**
 * The categories a group can go to: none, the user's of its kind, and new
 * ones (the defaults the user doesn't have, and [extra], the file's own name).
 */
@Composable
private fun CategoryMenu(
    kinds: List<CategoryKind>,
    wallet: Wallet,
    newCategories: List<Category>,
    extra: String?,
    onChoose: (CategoryKind?, CategoryValue) -> Unit,
    anchor: @Composable (open: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    anchor { open = true }
    if (open) {
        // Above the categories: none, and the new ones the import would make, each for its kind.
        val fresh = kinds.flatMap { kind ->
            val names = newCategories.filter { it.kind == kind }.map { it.name }.toMutableList()
            val taken = wallet.categories.any { it.kind == kind && it.name.equals(extra, ignoreCase = true) }
            if (extra != null && extra !in names && !taken) names.add(0, extra)
            names.map { kind to it }
        }
        CategoryPickerSheet(
            wallet = wallet,
            kind = kinds.singleOrNull(),
            selectedId = null,
            onPick = { onChoose(it.kind, CategoryValue.Existing(it.id)) },
            onDismiss = { open = false },
            extras = listOf(CategoryPickerExtra(stringResource(R.string.feature_imports_no_category), false) { onChoose(null, CategoryValue.None) }) +
                fresh.map { (kind, name) ->
                    CategoryPickerExtra(stringResource(R.string.feature_imports_new_category_named, name), false) { onChoose(kind, CategoryValue.New(name)) }
                },
        )
    }
}

/** An account's name as the review names it: chosen, or a new one by its name. */
private fun roleAccount(role: String?, preview: ImportPreview, wallet: Wallet, choices: Choices): String {
    val g = preview.accounts.firstOrNull { it.key == role } ?: return ""
    val id = choices.accounts[g.key] ?: AccountPick.NEW
    return wallet.account(id)?.name ?: choices.newNames[g.key]?.trim()?.ifEmpty { null } ?: g.newName
}

@Composable
private fun RowLine(r: ImportRow, p: ImportPreview, wallet: Wallet, choices: Choices) {
    val format = rememberMoneyFormat(wallet.settings)
    val muted = r.status != ImportRowStatus.New
    val card = p.source == ImportSource.IcCard
    val what = when {
        r.status == ImportRowStatus.Start -> ""
        card && r.kind == TransactionKind.Transfer -> stringResource(R.string.feature_imports_top_up)
        else -> r.text
    }
    val status = rowStatus(r, p, wallet, choices)
    Row(
        Modifier.fillMaxWidth().background(WalletTheme.colors.surface, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(what.ifEmpty { null }, r.lines).joinToString(" · ").ifEmpty { status },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (muted) WalletTheme.colors.muted else WalletTheme.colors.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                format.date(r.date) + (r.time?.let { " $it" } ?: "") + " · " + status,
                style = MaterialTheme.typography.bodySmall,
                color = if (!muted && !r.rule) WalletTheme.colors.attention else WalletTheme.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (r.status != ImportRowStatus.Start) {
                Text(format.money(r.amount, signed = true), style = MaterialTheme.typography.bodyMedium.figures(), color = if (muted) WalletTheme.colors.muted else WalletTheme.colors.text)
            }
            r.balance?.takeIf { card }?.let { Text(format.money(it), style = MaterialTheme.typography.bodySmall.figures(), color = WalletTheme.colors.muted) }
        }
    }
}

/** How a row would be recorded. */
@Composable
private fun rowStatus(r: ImportRow, p: ImportPreview, wallet: Wallet, choices: Choices): String = when (r.status) {
    ImportRowStatus.Imported -> stringResource(R.string.feature_imports_already_imported)
    ImportRowStatus.Matched -> r.matchedAccountId?.let { stringResource(R.string.feature_imports_already_recorded_in, wallet.account(it)?.name.orEmpty()) }
        ?: stringResource(R.string.feature_imports_already_recorded)
    ImportRowStatus.Start -> stringResource(R.string.feature_imports_start_unknown)
    ImportRowStatus.None -> stringResource(R.string.feature_imports_nothing_paid)
    ImportRowStatus.Skipped -> stringResource(R.string.feature_imports_not_imported) + (r.account?.let { " · $it" } ?: "")
    ImportRowStatus.New -> when {
        r.account != null && r.toAccount != null -> stringResource(R.string.feature_imports_transfer, r.account!!, r.toAccount!!)
        r.account != null -> r.account!!
        r.kind == TransactionKind.Transfer && p.source == ImportSource.IcCard ->
            stringResource(R.string.feature_imports_transfer, roleAccount("top-up", p, wallet, choices), stringResource(R.string.feature_imports_role_card))
        r.kind == TransactionKind.Transfer -> stringResource(R.string.feature_imports_transfer, roleAccount(r.fromRole, p, wallet, choices), roleAccount(r.toRole, p, wallet, choices))
        !r.rule -> stringResource(R.string.feature_imports_no_rule)
        r.kind == TransactionKind.Expense -> stringResource(R.string.feature_imports_expense)
        else -> stringResource(R.string.feature_imports_income_kind)
    }
}

/** The statement's (or card's) balance against its account in the app, and how they come to agree. */
@Composable
private fun BalanceNote(source: ImportSource, b: ImportBalance, wallet: Wallet, match: Boolean, done: Boolean, newName: String) {
    val format = rememberMoneyFormat(wallet.settings)
    val account = wallet.account(b.accountId)?.name ?: newName
    val lines = buildList {
        if (source == ImportSource.IcCard) {
            add(stringResource(R.string.feature_imports_card_balance, format.money(b.actual), account, format.money(b.app)) +
                if (b.difference == 0L) " " + stringResource(R.string.feature_imports_they_match) else "")
            when {
                done && b.adjusted != 0L -> add(stringResource(R.string.feature_imports_card_adjusted, account, format.money(b.adjusted, signed = true), format.money(b.initialBalance)))
                !done && b.difference != 0L && match -> add(stringResource(R.string.feature_imports_card_will_adjust, account, format.money(b.difference, signed = true), format.money(b.suggestedInitialBalance)))
                !done && b.difference != 0L -> add(stringResource(R.string.feature_imports_apart, format.money(kotlin.math.abs(b.difference))))
            }
        } else {
            val day = b.to?.let { format.date(it) }.orEmpty()
            val from = b.from?.let { format.date(it) }.orEmpty()
            add(stringResource(R.string.feature_imports_statement_balance, day, format.money(b.actual), format.money(b.app)) +
                if (b.difference == 0L) " " + stringResource(R.string.feature_imports_they_match) else "")
            val adjust = b.difference - b.misplaced
            when {
                done && b.adjusted != 0L -> add(stringResource(R.string.feature_imports_bank_adjusted, account, format.money(b.adjusted, signed = true), format.money(b.initialBalance)))
                !done && adjust != 0L && match -> add(stringResource(R.string.feature_imports_bank_will_adjust, account, format.money(adjust, signed = true), format.money(b.suggestedInitialBalance), from))
                !done && adjust != 0L -> add(stringResource(R.string.feature_imports_bank_apart, format.money(kotlin.math.abs(adjust)), from, format.money(b.suggestedInitialBalance)))
            }
            if (b.misplaced != 0L) add(stringResource(R.string.feature_imports_misplaced, format.money(b.misplaced, signed = true), account))
        }
    }
    Notice(lines.joinToString("\n"), tone = if (b.difference != 0L) NoticeTone.Attention else NoticeTone.Info)
}

/** The import's buttons, kept at the bottom of the screen however long the review. */
@Composable
private fun ImportBar(stage: ReviewStage.Ready, actions: ReviewActions, navigation: ImportsNavigation) {
    val p = stage.preview
    val c = stage.choices
    val label = when {
        p.new == 0 -> stringResource(R.string.feature_imports_import_nothing)
        p.source == ImportSource.PayPay || p.source == ImportSource.Vpass -> pluralStringResource(R.plurals.feature_imports_import_payments, p.new, p.new)
        p.source == ImportSource.Mufg -> pluralStringResource(R.plurals.feature_imports_import_rows, p.new, p.new)
        p.source == ImportSource.BudgetBakers -> pluralStringResource(R.plurals.feature_imports_import_records, p.new, p.new)
        else -> pluralStringResource(R.plurals.feature_imports_import_entries, p.new, p.new)
    }
    // A balance to match is something to do even with nothing new.
    val balanceOnly = c.matchBalance && p.balance?.let { (it.difference - it.misplaced) != 0L && (it.accountId != null || p.source == ImportSource.IcCard) } == true
    val enabled = !stage.busy && (p.new > 0 || balanceOnly || stage.checking)
    Column(Modifier.fillMaxWidth().background(WalletTheme.colors.surface)) {
        HorizontalDivider(color = WalletTheme.colors.border)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = navigation::back, enabled = !stage.busy) { Text(stringResource(DesignSystemR.string.designsystem_cancel)) }
            Button(
                onClick = actions::submit,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) {
                if (stage.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WalletTheme.colors.onAction)
                    Box(Modifier.size(8.dp))
                    Text(stringResource(R.string.feature_imports_importing))
                } else {
                    Text(label)
                }
            }
        }
    }
}

private fun LazyListScope.done(stage: ReviewStage.Done, wallet: Wallet, actions: ReviewActions, navigation: ImportsNavigation) {
    val undone = stage.undo as? UndoState.Undone
    item { if (undone == null) ImportSummary(stage.result, navigation) else Notice(pluralStringResource(R.plurals.feature_imports_undone, undone.ids.size, undone.ids.size)) }
    if (undone == null) {
        stage.result.balance?.takeIf { it.accountId != null }?.let { b ->
            item { BalanceNote(stage.preview.source, b, wallet, stage.matchBalance, done = true, newName = "") }
        }
    }
    (stage.undo as? UndoState.Failed)?.let { f ->
        item { ProblemMessage(f.message.ifEmpty { null }?.let { stringResource(R.string.feature_imports_undo_failed, serverText(it)) } ?: stringResource(R.string.feature_imports_offline)) }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (stage.result.batchId != null) {
                OutlinedButton(onClick = actions::undo, enabled = stage.undo != UndoState.Busy) {
                    Text(
                        stringResource(
                            when {
                                stage.undo == UndoState.Busy -> R.string.feature_imports_undoing
                                undone != null -> R.string.feature_imports_restore
                                else -> R.string.feature_imports_undo
                            },
                        ),
                    )
                }
            }
            Button(
                onClick = navigation::back,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) { Text(stringResource(DesignSystemR.string.designsystem_done)) }
        }
    }
}

/** What an import did, in one sentence; records left without a category can go to Claude. */
@Composable
internal fun ImportSummary(result: ImportResult, navigation: ImportsNavigation) {
    val parts = buildList {
        add(pluralStringResource(R.plurals.feature_imports_imported, result.imported, result.imported))
        if (result.duplicates > 0) add(pluralStringResource(R.plurals.feature_imports_skipped_imported, result.duplicates, result.duplicates))
        if (result.matched > 0) add(pluralStringResource(R.plurals.feature_imports_skipped_matched, result.matched, result.matched))
        if (result.skipped > 0) add(pluralStringResource(R.plurals.feature_imports_left_out, result.skipped, result.skipped))
        if (result.accountsCreated > 0) add(pluralStringResource(R.plurals.feature_imports_created_accounts, result.accountsCreated, result.accountsCreated))
        if (result.categoriesCreated > 0) add(pluralStringResource(R.plurals.feature_imports_created_categories, result.categoriesCreated, result.categoriesCreated))
    }
    Column(
        Modifier.fillMaxWidth().background(WalletTheme.colors.sunken, MaterialTheme.shapes.small).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(WalletIcons.check, contentDescription = null, tint = WalletTheme.colors.positive, size = 20.dp)
            Text(parts.joinToString(", ") + ".")
        }
        for (problem in result.problems) Text("• " + serverText(problem), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        if (result.imported > 0) {
            Text(
                stringResource(R.string.feature_imports_sort_with_claude),
                style = MaterialTheme.typography.bodySmall,
                color = WalletTheme.colors.muted,
                modifier = Modifier.clickable { navigation.open("settings?section=claude") },
            )
        }
    }
}

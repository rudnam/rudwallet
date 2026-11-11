package com.rudnam.wallet.feature.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.CategoryPickerExtra
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText

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
}

/**
 * An import before anything is recorded: where each group of records goes
 * (an account each way of paying, role or account in the file; a category
 * each shop or category), how each row would be recorded, and the balance
 * against the statement or card. Then what the import did.
 */
@Composable
internal fun ImportReviewScreen(source: ImportSource, wallet: Wallet?, stage: ReviewStage, actions: ReviewActions, navigation: ImportsNavigation) {
    ImportPage(sourceName(source), navigation) {
        if (wallet == null) {
            item { Loading() }
            return@ImportPage
        }
        when (stage) {
            ReviewStage.Loading -> item {
                Loading(stringResource(if (source == ImportSource.IcCard) R.string.feature_imports_reading_card else R.string.feature_imports_reading_file))
            }
            ReviewStage.Unreadable -> item { Problem(stringResource(R.string.feature_imports_unreadable), onRetry = null) }
            is ReviewStage.Failed -> item {
                Problem(
                    if (stage.offline) stringResource(R.string.feature_imports_offline) else stage.message.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_imports_failed),
                    onRetry = actions::retry,
                )
            }
            is ReviewStage.Ready -> review(stage, wallet, actions, navigation)
            is ReviewStage.Done -> done(stage, wallet, navigation)
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

@Composable
private fun Problem(text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = WalletTheme.colors.attention)
        onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.feature_imports_try_again)) } }
    }
}

@Composable
private fun Note(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
            .background(if (warn) WalletTheme.colors.attentionSoft else WalletTheme.colors.sunken, MaterialTheme.shapes.small)
            .padding(12.dp),
    )
}

private fun LazyListScope.review(stage: ReviewStage.Ready, wallet: Wallet, actions: ReviewActions, navigation: ImportsNavigation) {
    val p = stage.preview
    val c = stage.choices
    if (p.records == 0) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (p.source == ImportSource.PayPay) R.string.feature_imports_no_payments else R.string.feature_imports_no_records))
                OutlinedButton(onClick = navigation::back) { Text(stringResource(R.string.feature_imports_choose_another)) }
            }
        }
        return
    }
    item { Intro(p, wallet) }
    if (p.gap) item { Note(stringResource(R.string.feature_imports_gap_review), warn = true) }
    item(key = "accounts") {
        WalletPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (g in p.accounts) AccountChoice(p.source, g, wallet, c, actions)
            }
        }
    }
    if (p.categories.isNotEmpty()) {
        item {
            Text(
                stringResource(
                    when (p.source) {
                        ImportSource.PayPay -> R.string.feature_imports_paypay_categories
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
        item { Note(stringResource(R.string.feature_imports_problems) + "\n" + p.problems.joinToString("\n") { "• $it" }, warn = true) }
    }
    if (p.rows.isNotEmpty()) {
        item {
            Text(
                stringResource(if (p.source == ImportSource.IcCard) R.string.feature_imports_card_entries else R.string.feature_imports_statement_rows),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
            )
        }
        // A card's entries come oldest first; the newest shows first, as on the card page. A statement stays in its order.
        val rows = if (p.source == ImportSource.IcCard) p.rows.asReversed() else p.rows
        items(rows.size, key = { "row$it" }) { i -> RowLine(rows[i], p, wallet, c) }
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
    stage.error?.let { item { Problem(it.ifEmpty { null }?.let { m -> serverText(m) } ?: stringResource(R.string.feature_imports_offline), onRetry = null) } }
    item { ImportButtons(stage, actions, navigation) }
}

@Composable
private fun Intro(p: ImportPreview, wallet: Wallet) {
    val format = rememberMoneyFormat(wallet.settings)
    val from = p.from?.let { format.date(it) }.orEmpty()
    val to = p.to?.let { format.date(it) }.orEmpty()
    val text = when (p.source) {
        ImportSource.PayPay -> pluralStringResource(R.plurals.feature_imports_payments_range, p.records, p.records, from, to)
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
    ImportRowStatus.New -> when {
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
    Note(lines.joinToString("\n"), warn = b.difference != 0L)
}

@Composable
private fun ImportButtons(stage: ReviewStage.Ready, actions: ReviewActions, navigation: ImportsNavigation) {
    val p = stage.preview
    val c = stage.choices
    val label = when (p.source) {
        ImportSource.PayPay -> if (AccountPick.SKIP in c.accounts.values) {
            stringResource(R.string.feature_imports_import_rest)
        } else {
            pluralStringResource(R.plurals.feature_imports_import_payments, p.records, p.records)
        }
        ImportSource.Mufg -> pluralStringResource(R.plurals.feature_imports_import_rows, p.new, p.new)
        ImportSource.BudgetBakers -> pluralStringResource(R.plurals.feature_imports_import_records, p.records, p.records)
        ImportSource.IcCard -> pluralStringResource(R.plurals.feature_imports_import_entries, p.new, p.new)
    }
    val enabled = !stage.busy && when (p.source) {
        ImportSource.Mufg -> p.new > 0
        ImportSource.IcCard -> p.new > 0 || (c.matchBalance && (p.balance?.difference ?: 0L) != 0L)
        else -> true
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = navigation::back, enabled = !stage.busy) { Text(stringResource(R.string.feature_imports_cancel)) }
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

private fun LazyListScope.done(stage: ReviewStage.Done, wallet: Wallet, navigation: ImportsNavigation) {
    item { ImportSummary(stage.result, navigation) }
    stage.result.balance?.takeIf { it.accountId != null }?.let { b ->
        item { BalanceNote(stage.preview.source, b, wallet, stage.matchBalance, done = true, newName = "") }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = navigation::back,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) { Text(stringResource(R.string.feature_imports_done)) }
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

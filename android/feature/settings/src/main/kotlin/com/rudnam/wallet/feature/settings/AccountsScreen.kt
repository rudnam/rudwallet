package com.rudnam.wallet.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.designsystem.theme.parseHexColor
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.kindName
import com.rudnam.wallet.core.ui.rememberMoneyFormat

/** Each kind of account: its icon and colour, and names people often use. */
internal data class KindMeta(val glyph: String, val color: String, val names: List<Int>)

internal fun kindMeta(kind: AccountKind) = when (kind) {
    AccountKind.Bank -> KindMeta("bank", "#3b6ea8", listOf(R.string.feature_settings_name_mufg, R.string.feature_settings_name_smbc, R.string.feature_settings_name_mizuho, R.string.feature_settings_name_rakuten_bank, R.string.feature_settings_name_japan_post))
    AccountKind.CreditCard -> KindMeta("card", "#c8463d", listOf(R.string.feature_settings_name_paypay_card, R.string.feature_settings_name_rakuten_card, R.string.feature_settings_name_amex, R.string.feature_settings_name_smbc_card))
    AccountKind.EMoney -> KindMeta("phone", "#d4553f", listOf(R.string.feature_settings_name_paypay, R.string.feature_settings_name_suica, R.string.feature_settings_name_pasmo, R.string.feature_settings_name_icoca, R.string.feature_settings_name_nanaco))
    AccountKind.Cash -> KindMeta("money", "#4f8a4c", listOf(R.string.feature_settings_name_cash))
    AccountKind.Savings -> KindMeta("savings", "#b08a3e", listOf(R.string.feature_settings_name_savings, R.string.feature_settings_name_emergency))
    AccountKind.Other -> KindMeta("other", "#6f6c63", emptyList())
}

/** The colours offered for accounts and categories, as on the website. */
internal val swatches = listOf("#c8463d", "#d97757", "#b08a3e", "#4f8a4c", "#2a8a8a", "#3b6ea8", "#5b4a8a", "#a8508a", "#6f6c63")

/** An account's mark: its kind's glyph in its own colour. */
@Composable
internal fun AccountMark(account: Account, modifier: Modifier = Modifier) =
    CategoryIcon(Category(account.id, account.name, CategoryKind.Expense, kindMeta(account.kind).glyph, account.color), modifier)

@Composable
internal fun ChangeMessages(messages: List<ChangeMessage>, shown: (ChangeMessage) -> Unit, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        shown(message)
        snackbar.showSnackbar(
            when (message) {
                ChangeMessage.Saved -> resources.getString(R.string.feature_settings_saved).also { haptics.success() }
                is ChangeMessage.Added -> resources.getString(R.string.feature_settings_added, message.name).also { haptics.success() }
                ChangeMessage.Deleted -> resources.getString(R.string.feature_settings_deleted)
                ChangeMessage.Offline -> resources.getString(R.string.feature_settings_offline).also { haptics.error() }
            },
        )
    }
}

@Composable
internal fun AccountsDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: AccountsViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    ChangeMessages(messages, viewModel::messageShown, snackbar)
    AccountsScreen(wallet, editor, viewModel, navigation)
}

/** Everywhere the money lives: each account with its kind and balance; tapping one changes it. */
@Composable
internal fun AccountsScreen(wallet: Wallet?, editor: AccountEditor?, actions: AccountsActions, navigation: SettingsNavigation) {
    SettingsPage(
        stringResource(R.string.feature_settings_accounts),
        navigation,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::create,
                icon = { LineIcon(WalletIcons.plus, contentDescription = null, stroke = 2.2f) },
                text = { Text(stringResource(R.string.feature_settings_add_account)) },
                containerColor = WalletTheme.colors.action,
                contentColor = WalletTheme.colors.onAction,
                shape = RoundedCornerShape(18.dp),
                elevation = FloatingActionButtonDefaults.loweredElevation(),
            )
        },
    ) {
        item { Intro(stringResource(R.string.feature_settings_accounts_intro)) }
        if (wallet != null) {
            item {
                val format = rememberMoneyFormat(wallet.settings)
                WalletPanel(Modifier.fillMaxWidth()) {
                    wallet.accounts.forEachIndexed { i, a ->
                        AccountRow(a, format, onClick = { actions.edit(a) })
                        if (i < wallet.accounts.lastIndex) HorizontalDivider(Modifier.padding(start = 72.dp), color = WalletTheme.colors.border)
                    }
                }
            }
        }
    }
    if (wallet != null && editor != null) AccountSheet(wallet, editor, actions)
}

@Composable
internal fun Intro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun AccountRow(account: Account, format: MoneyFormat, onClick: () -> Unit) {
    val edit = stringResource(R.string.feature_settings_edit_account_named, account.name)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).semantics { onClick(label = edit) { onClick(); true } },
        colors = ListItemDefaults.colors(containerColor = WalletTheme.colors.surface),
        leadingContent = { AccountMark(account) },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (account.archived) {
                    Text(
                        stringResource(R.string.feature_settings_archived),
                        style = MaterialTheme.typography.labelSmall,
                        color = WalletTheme.colors.muted,
                        modifier = Modifier.background(WalletTheme.colors.sunken, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
        supportingContent = { Text(stringResource(kindName(account.kind)), color = WalletTheme.colors.muted) },
        trailingContent = { Text(format.money(account.balance), style = MaterialTheme.typography.bodyLarge.figures()) },
    )
}

/**
 * Adding or changing an account: its kind, name (with suggestions), what's in
 * it (for a card, what's owed), its colour, and whether it's archived.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountSheet(wallet: Wallet, editor: AccountEditor, actions: AccountsActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val kind = editor.kind
    val meta = kind?.let(::kindMeta)
    val currency = wallet.settings.currency
    val card = kind == AccountKind.CreditCard
    val defaultName = kind?.let { stringResource(meta!!.names.firstOrNull() ?: kindName(it)) }.orEmpty()
    ModalBottomSheet(onDismissRequest = actions::close, sheetState = sheet, containerColor = WalletTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editor.id == null) R.string.feature_settings_new_account else R.string.feature_settings_edit_account),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (editor.id == null) R.string.feature_settings_what_kind else R.string.feature_settings_type),
                style = MaterialTheme.typography.labelLarge,
                color = if (editor.problem == AccountProblem.NoKind) WalletTheme.colors.danger else WalletTheme.colors.muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in AccountKind.entries) {
                    FilterChip(
                        selected = kind == k,
                        onClick = { actions.setKind(k) },
                        label = { Text(stringResource(kindName(k))) },
                        leadingIcon = { LineIcon(WalletIcons.glyphs.getValue(kindMeta(k).glyph), contentDescription = null, size = 18.dp) },
                    )
                }
            }
            if (kind != null && meta != null) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = actions::setName,
                    label = { Text(stringResource(R.string.feature_settings_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editor.id == null && meta.names.isNotEmpty()) {
                    val label = stringResource(R.string.feature_settings_suggested_names)
                    FlowRow(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in meta.names) {
                            val name = stringResource(n)
                            SuggestionChip(onClick = { actions.setName(name) }, label = { Text(name) })
                        }
                    }
                }
                val balanceLabel = when {
                    editor.id != null && card -> R.string.feature_settings_owed_start
                    editor.id != null -> R.string.feature_settings_balance_start
                    card -> R.string.feature_settings_owed_today
                    else -> R.string.feature_settings_balance_today
                }
                val detail = when {
                    editor.id != null -> R.string.feature_settings_balance_start_detail
                    card -> R.string.feature_settings_owed_today_detail
                    else -> R.string.feature_settings_balance_today_detail
                }
                OutlinedTextField(
                    value = editor.balance,
                    onValueChange = actions::setBalance,
                    label = { Text(stringResource(balanceLabel, currency)) },
                    placeholder = { Text("0") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.figures(),
                    isError = editor.problem == AccountProblem.BadBalance,
                    supportingText = { Text(stringResource(if (editor.problem == AccountProblem.BadBalance) R.string.feature_settings_enter_balance else detail)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                ColourPicker(editor.color.ifEmpty { meta.color }, actions::setColor)
                if (editor.id != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .selectable(selected = editor.archived, role = Role.Switch, onClick = { actions.setArchived(!editor.archived) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.feature_settings_archived_detail), Modifier.weight(1f))
                        Switch(checked = editor.archived, onCheckedChange = null)
                    }
                }
            }
            editor.refused?.let { Text(it, color = WalletTheme.colors.danger, style = MaterialTheme.typography.bodySmall) }
            SheetButtons(
                deletable = editor.id != null,
                busy = editor.busy,
                saveLabel = stringResource(if (editor.id == null) R.string.feature_settings_add_account else R.string.feature_settings_save),
                onDelete = { actions.askDelete(true) },
                onSave = { actions.save(defaultName, meta?.color ?: "#6f6c63") },
            )
        }
    }
    if (editor.confirmingDelete) {
        ConfirmDelete(stringResource(R.string.feature_settings_delete_account_question, editor.name), onConfirm = actions::delete, onCancel = { actions.askDelete(false) })
    }
}

/** Delete on the left (for one that exists), the main action on the right. */
@Composable
internal fun SheetButtons(deletable: Boolean, busy: Boolean, saveLabel: String, onDelete: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (deletable) {
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.feature_settings_delete), color = WalletTheme.colors.danger) }
        }
        Box(Modifier.weight(1f))
        Button(
            onClick = onSave,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WalletTheme.colors.onAction) else Text(saveLabel)
        }
    }
}

@Composable
internal fun ConfirmDelete(question: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(question) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.feature_settings_delete), color = WalletTheme.colors.danger) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

/** The colours offered, and the one chosen if it's another (one picked on the website, say). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ColourPicker(color: String, onChoose: (String) -> Unit) {
    val haptics = rememberHaptics()
    val options = if (swatches.any { it.equals(color, ignoreCase = true) }) swatches else swatches + color
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_settings_colour), style = MaterialTheme.typography.labelLarge, color = WalletTheme.colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in options) {
                val on = c.equals(color, ignoreCase = true)
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(c) ?: Color.Gray)
                        .border(if (on) 3.dp else 0.dp, if (on) WalletTheme.colors.text else Color.Transparent, CircleShape)
                        .selectable(selected = on, role = Role.RadioButton, onClick = {
                            haptics.select()
                            onChoose(c)
                        })
                        .semantics { contentDescription = c },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) LineIcon(WalletIcons.check, contentDescription = null, tint = Color.White, size = 18.dp)
                }
            }
        }
    }
}

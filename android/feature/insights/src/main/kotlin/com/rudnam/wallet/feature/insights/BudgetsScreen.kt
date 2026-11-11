package com.rudnam.wallet.feature.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryPickerSheet
import com.rudnam.wallet.core.ui.CategoryPickerExtra
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText

@Composable
internal fun BudgetsDestination(navigation: InsightsNavigation, snackbar: SnackbarHostState, viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val haptics = rememberHaptics()

    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        val text = when (message) {
            BudgetsMessage.Offline -> resources.getString(R.string.feature_insights_budget_offline).also { haptics.error() }
            is BudgetsMessage.Deleted -> resources.getString(R.string.feature_insights_deleted, message.name)
        }
        snackbar.showSnackbar(text)
    }

    BudgetsScreen(state, editor, viewModel, navigation)
}

/** Monthly spending limits, per category or for everything (docs/design, "budgets"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetsScreen(state: BudgetsUiState, editor: BudgetEditor?, actions: BudgetsActions, navigation: InsightsNavigation) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = state as? BudgetsUiState.Ready
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.feature_insights_budgets))
                        if (ready != null) {
                            Text(rememberMoneyFormat(ready.wallet.settings).date(ready.month.atDay(1), "yMMMM"), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigation::back) { LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_insights_back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
        floatingActionButton = {
            if (ready != null && ready.budgets.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = actions::create,
                    icon = { LineIcon(WalletIcons.plus, contentDescription = null, stroke = 2.2f) },
                    text = { Text(stringResource(R.string.feature_insights_new_budget)) },
                    containerColor = WalletTheme.colors.action,
                    contentColor = WalletTheme.colors.onAction,
                    shape = RoundedCornerShape(18.dp),
                    elevation = FloatingActionButtonDefaults.loweredElevation(),
                )
            }
        },
    ) { padding ->
        when {
            ready == null -> PageLoading(Modifier.fillMaxSize().padding(padding))
            ready.budgets.isEmpty() -> NoBudgets(actions, Modifier.padding(padding))
            else -> {
                val format = rememberMoneyFormat(ready.wallet.settings)
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ready.budgets, key = { it.budget.id }) { row -> BudgetCard(row, ready.wallet, format, actions) }
                }
            }
        }
    }
    if (ready != null && editor != null) BudgetSheet(ready.wallet, editor, actions)
}

@Composable
private fun NoBudgets(actions: BudgetsActions, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BudgetMark(null, allSpending = true, size = 56.dp)
        Text(stringResource(R.string.feature_insights_no_budgets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.feature_insights_no_budgets_detail), color = WalletTheme.colors.muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = actions::create,
            colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
        ) { Text(stringResource(R.string.feature_insights_create_budget)) }
    }
}

/** A budget: its mark, name and category, and how much of it this month has used. Tapping it changes it. */
@Composable
private fun BudgetCard(row: BudgetRow, wallet: Wallet, format: MoneyFormat, actions: BudgetsActions) {
    val b = row.budget
    val category = wallet.category(b.categoryId)
    val edit = stringResource(R.string.feature_insights_edit_budget_named, b.name)
    // TalkBack says what a tap does: "Double tap to edit budget Eating out".
    WalletPanel(Modifier.fillMaxWidth().semantics { onClick(label = edit) { actions.edit(b); true } }, onClick = { actions.edit(b) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BudgetMark(category, allSpending = b.categoryId == null, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(category?.name ?: stringResource(R.string.feature_insights_all_spending), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                }
                LineIcon(WalletIcons.chevron, contentDescription = null, tint = WalletTheme.colors.muted, size = 18.dp)
            }
            val ofPattern = stringResource(R.string.feature_insights_of)
            val leftPattern = stringResource(R.string.feature_insights_left)
            val overPattern = stringResource(R.string.feature_insights_over_by)
            Meter(
                spent = row.spent,
                limit = b.amount,
                money = format::money,
                of = { a, c -> String.format(ofPattern, a, c) },
                left = { String.format(leftPattern, it) },
                overBy = { String.format(overPattern, it) },
            )
        }
    }
}

/** Making or changing a budget: its category, monthly limit and name; a budget being changed can be deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetSheet(wallet: Wallet, editor: BudgetEditor, actions: BudgetsActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val format = rememberMoneyFormat(wallet.settings)
    val allSpending = stringResource(R.string.feature_insights_all_spending)
    val chosen = wallet.category(editor.categoryId)
    val defaultName = chosen?.name ?: allSpending
    ModalBottomSheet(onDismissRequest = actions::close, sheetState = sheet, containerColor = WalletTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editor.id == null) R.string.feature_insights_new_budget else R.string.feature_insights_edit_budget),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            // The category: the picker, with "All spending" above the categories.
            var open by rememberSaveable { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value = wallet.categoryPath(chosen?.id) ?: allSpending,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.feature_insights_category)) },
                    leadingIcon = { BudgetMark(chosen, allSpending = chosen == null, size = 28.dp) },
                    trailingIcon = { LineIcon(WalletIcons.expand, contentDescription = null, size = 18.dp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true })
            }
            if (open) {
                CategoryPickerSheet(
                    wallet = wallet,
                    kind = CategoryKind.Expense,
                    selectedId = chosen?.id,
                    onPick = { actions.setCategory(it.id) },
                    onDismiss = { open = false },
                    title = stringResource(R.string.feature_insights_category),
                    extras = listOf(CategoryPickerExtra(allSpending, chosen == null) { actions.setCategory(null) }),
                )
            }

            val exponent = wallet.settings.currencyExponent
            OutlinedTextField(
                value = editor.amount,
                onValueChange = actions::setAmount,
                label = { Text(stringResource(R.string.feature_insights_monthly_limit, wallet.settings.currency)) },
                prefix = { Text(format.symbol) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.figures(),
                isError = editor.invalidAmount,
                supportingText = if (editor.invalidAmount) {
                    { Text(stringResource(R.string.feature_insights_enter_limit)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = if (exponent == 0) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.name,
                onValueChange = actions::setName,
                label = { Text(stringResource(R.string.feature_insights_name_optional)) },
                placeholder = { Text(defaultName) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.save(defaultName) }),
                modifier = Modifier.fillMaxWidth(),
            )
            editor.refused?.let { Text(serverText(it), color = WalletTheme.colors.danger, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor.id != null) {
                    OutlinedButton(onClick = { actions.askDelete(true) }, enabled = !editor.busy) {
                        Text(stringResource(R.string.feature_insights_delete), color = WalletTheme.colors.danger)
                    }
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = { actions.save(defaultName) },
                    enabled = !editor.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                ) {
                    if (editor.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WalletTheme.colors.onAction)
                    else Text(stringResource(R.string.feature_insights_save))
                }
            }
        }
    }
    if (editor.confirmingDelete) {
        val name = editor.name.ifEmpty { defaultName }
        AlertDialog(
            onDismissRequest = { actions.askDelete(false) },
            text = { Text(stringResource(R.string.feature_insights_delete_question, name)) },
            confirmButton = { TextButton(onClick = actions::delete) { Text(stringResource(R.string.feature_insights_delete), color = WalletTheme.colors.danger) } },
            dismissButton = { TextButton(onClick = { actions.askDelete(false) }) { Text(stringResource(R.string.feature_insights_cancel)) } },
        )
    }
}

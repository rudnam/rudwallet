package com.rudnam.wallet.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.ui.AccountCard
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.RecordDetail
import com.rudnam.wallet.core.ui.recordDays
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import java.time.Instant
import com.rudnam.wallet.core.ui.ServerText

/** Where the account page's taps lead. */
interface AccountNavigation {
    fun back()

    /** Settings › Accounts, where the account is edited (on the web for now). */
    fun editAccounts()

    fun openRecord(record: Transaction)
}

@Composable
internal fun AccountDestination(navigation: AccountNavigation, snackbar: SnackbarHostState, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val haptics = rememberHaptics()

    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        val ready = state as? AccountUiState.Ready
        val format = ready?.let { MoneyFormat(resources.configuration.locales[0], it.wallet.settings) }
        val text = when (message) {
            is AccountMessage.Counted -> resources.getString(R.string.feature_account_counted, format?.money(message.balance).orEmpty()).also { haptics.success() }
            is AccountMessage.AlreadyRight -> resources.getString(R.string.feature_account_already_right, format?.money(message.balance).orEmpty()).also { haptics.success() }
            AccountMessage.Offline -> resources.getString(R.string.feature_account_offline)
            is AccountMessage.Failed -> ServerText.translate(message.message, resources.configuration.locales[0])
        }
        snackbar.showSnackbar(text)
    }

    AccountScreen(
        state = state,
        counting = counting,
        navigation = navigation,
        actions = object : AccountActions {
            override fun count() = viewModel.startCounting()
            override fun type(text: String) = viewModel.typeCount(text)
            override fun save() = viewModel.saveCount()
            override fun cancel() = viewModel.cancelCounting()
        },
    )
}

/** What Count cash's controls do. */
internal interface AccountActions {
    fun count()
    fun type(text: String)
    fun save()
    fun cancel()
}

/** One account (docs/design, "account"): its card in full, this month in and out, and its records. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    state: AccountUiState,
    counting: AccountViewModel.CountState?,
    navigation: AccountNavigation,
    actions: AccountActions,
    now: Instant = Instant.now(),
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = state as? AccountUiState.Ready
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(ready?.account?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = navigation::back) { LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_account_back)) }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = navigation::editAccounts) {
                            LineIcon(WalletIcons.edit, contentDescription = stringResource(R.string.feature_account_edit, ready.account.name))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
    ) { padding ->
        when (state) {
            AccountUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            AccountUiState.Gone -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.feature_account_gone), color = WalletTheme.colors.muted)
            }
            is AccountUiState.Ready -> Account(state, navigation, actions, now, Modifier.padding(padding))
        }
    }
    if (ready != null && counting != null) CountCashDialog(ready, counting, actions)
}

@Composable
private fun Account(state: AccountUiState.Ready, navigation: AccountNavigation, actions: AccountActions, now: Instant, modifier: Modifier) {
    val format = rememberMoneyFormat(state.wallet.settings)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "card") { AccountCard(state.account, format) }
        if (state.account.kind == AccountKind.Cash) {
            // Cash can't fill itself in: what's really there becomes the balance.
            item(key = "count") {
                OutlinedButton(onClick = actions::count, modifier = Modifier.fillMaxWidth()) {
                    LineIcon(WalletIcons.count, contentDescription = null, size = 18.dp)
                    Box(Modifier.width(8.dp))
                    Text(stringResource(R.string.feature_account_count))
                }
            }
        }
        item(key = "month") { InAndOut(state, format) }
        if (state.records.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.feature_account_nothing), color = WalletTheme.colors.muted) }
        } else {
            recordDays(state.records, state.wallet, format, navigation::openRecord, now, detail = RecordDetail.Time)
        }
    }
}

@Composable
private fun InAndOut(state: AccountUiState.Ready, format: MoneyFormat) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Figure(stringResource(R.string.feature_account_out, format.monthName(state.month)), format.money(state.out), Modifier.weight(1f))
            VerticalDivider(color = WalletTheme.colors.border)
            Figure(stringResource(R.string.feature_account_in), format.money(state.into), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.titleLarge.figures())
    }
}

/** Count cash: what's in the wallet now; the difference from the app's balance is recorded. */
@Composable
private fun CountCashDialog(state: AccountUiState.Ready, counting: AccountViewModel.CountState, actions: AccountActions) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val exponent = state.wallet.settings.currencyExponent
    AlertDialog(
        onDismissRequest = actions::cancel,
        title = { Text(stringResource(R.string.feature_account_count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.feature_account_count_explain, format.money(state.account.balance)), color = WalletTheme.colors.muted)
                OutlinedTextField(
                    value = counting.text,
                    onValueChange = actions::type,
                    label = { Text(stringResource(R.string.feature_account_amount, state.wallet.settings.currency)) },
                    prefix = { Text(format.symbol) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.figures(),
                    isError = counting.invalid,
                    supportingText = if (counting.invalid) {
                        { Text(stringResource(if (exponent == 0) R.string.feature_account_no_amount_yen else R.string.feature_account_no_amount_cents)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = if (exponent == 0) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.save() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = actions::save, enabled = !counting.busy) { Text(stringResource(R.string.feature_account_save)) } },
        dismissButton = { TextButton(onClick = actions::cancel) { Text(stringResource(R.string.feature_account_cancel)) } },
    )
}

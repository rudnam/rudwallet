package com.rudnam.wallet.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.PageLoading
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.SetAsideRecord
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.ServerText
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import java.time.Instant

@Composable
internal fun DuplicatesDestination(onBack: () -> Unit, snackbar: SnackbarHostState, viewModel: DuplicatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val res = LocalResources.current
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        val text = when (message) {
            is DuplicatesMessage.BroughtBack ->
                res.getString(R.string.feature_activity_brought_back, message.payee.ifEmpty { res.getString(R.string.feature_activity_payment) })
            DuplicatesMessage.Offline -> res.getString(R.string.feature_activity_offline)
            is DuplicatesMessage.Failed -> res.getString(R.string.feature_activity_failed, ServerText.translate(message.message, res.configuration.locales[0]))
        }
        snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
    }
    when (val s = state) {
        DuplicatesUiState.Loading -> PageLoading(Modifier.fillMaxSize())
        is DuplicatesUiState.Ready -> DuplicatesScreen(s, viewModel, onBack)
    }
}

/**
 * "Caught duplicates": payments from notifications that looked like a record
 * already there, so they never reached "to check". Each shows what it
 * matched; "Not a duplicate" puts it in "to check".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicatesScreen(state: DuplicatesUiState.Ready, actions: DuplicatesActions, onBack: () -> Unit, now: Instant = Instant.now()) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_activity_duplicates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_activity_back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "about") {
                Text(stringResource(R.string.feature_activity_duplicates_about), style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted)
            }
            val records = state.records
            when {
                records == null -> item(key = "offline") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (state.offline) R.string.feature_activity_duplicates_offline else R.string.feature_activity_duplicates_failed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { actions.load() }) { Text(stringResource(R.string.feature_activity_try_again)) }
                    }
                }
                records.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(R.string.feature_activity_duplicates_none),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WalletTheme.colors.muted,
                    )
                }
                else -> items(records, key = { it.record.id }) { r -> CaughtRow(r, state, format, actions, now, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun CaughtRow(r: SetAsideRecord, state: DuplicatesUiState.Ready, format: MoneyFormat, actions: DuplicatesActions, now: Instant, modifier: Modifier = Modifier) {
    val colors = WalletTheme.colors
    val today = stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_today)
    val yesterday = stringResource(com.rudnam.wallet.core.ui.R.string.core_ui_yesterday)
    val t = r.record
    WalletPanel(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    t.payee.ifEmpty { stringResource(R.string.feature_activity_payment) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(format.dateTime(t.occurredAt, today, yesterday, now), t.sourceLabel.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            Text(amount(t, format), style = MaterialTheme.typography.bodyMedium.figures(), color = if (t.kind == TransactionKind.Income) colors.positive else colors.text)
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = MaterialTheme.shapes.small, color = colors.sunken) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val m = r.duplicateOf
                if (m != null) {
                    Text(stringResource(R.string.feature_activity_looked_like), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    Text(
                        listOfNotNull(
                            m.payee.ifEmpty { stringResource(R.string.feature_activity_payment) },
                            amount(m, format),
                            format.dateTime(m.occurredAt, today, yesterday, now),
                            state.wallet.account(m.accountId)?.name,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(stringResource(R.string.feature_activity_match_gone), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { actions.bringBack(r) }, enabled = !state.busy) {
                Text(stringResource(R.string.feature_activity_not_duplicate), maxLines = 1)
            }
        }
    }
}

private fun amount(t: Transaction, format: MoneyFormat): String =
    format.money(if (t.kind == TransactionKind.Income) t.amount else -t.amount, signed = true)

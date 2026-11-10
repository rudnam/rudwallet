package com.rudnam.wallet.feature.imports

import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.data.ic.IcCardException
import com.rudnam.wallet.core.data.ic.IcCardProblem
import com.rudnam.wallet.core.data.ic.IcCardReader
import com.rudnam.wallet.core.data.ic.IcCardTap
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.ImportRow
import com.rudnam.wallet.core.model.ImportRowStatus
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.serverText

/** Whether this phone can read a card now. */
enum class NfcState { NoNfc, Off, Ready }

/** NFC as it is, checked again each time the page comes back (from Android's NFC settings, say). */
@Composable
private fun rememberNfcState(): NfcState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(NfcState.Ready) }
    LifecycleResumeEffect(Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(context)
        state = when {
            nfc == null -> NfcState.NoNfc
            !nfc.isEnabled -> NfcState.Off
            else -> NfcState.Ready
        }
        onPauseOrDispose {}
    }
    return state
}

/**
 * Listens for a card while [on]: Android's reader mode, which only works
 * while the app is in front, so it's on only while this is shown and waiting.
 * The card is read on the reader's own thread, and [onTap] hears on the main one.
 */
@Composable
private fun CardReaderEffect(on: Boolean, onTap: (IcCardTap) -> Unit) {
    val activity = LocalActivity.current ?: return
    val tapped by rememberUpdatedState(onTap)
    if (!on) return
    DisposableEffect(activity) {
        val nfc = NfcAdapter.getDefaultAdapter(activity)
        nfc?.enableReaderMode(
            activity,
            { tag ->
                val tap = try {
                    IcCardTap.Read(IcCardReader.read(tag))
                } catch (e: IcCardException) {
                    IcCardTap.Failed(e.problem)
                }
                activity.runOnUiThread { tapped(tap) }
            },
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        onDispose { nfc?.disableReaderMode(activity) }
    }
}

@Composable
internal fun CardDestination(navigation: ImportsNavigation, viewModel: CardViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nfc = rememberNfcState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    CardReaderEffect(on = state.waiting && nfc == NfcState.Ready) { tap ->
        if (tap is IcCardTap.Read) haptics.success() else haptics.error()
        viewModel.tapped(tap)
    }
    CardScreen(
        wallet = wallet,
        state = state,
        nfc = nfc,
        actions = viewModel,
        onReview = {
            viewModel.review()
            navigation.review(ImportSource.IcCard, null)
        },
        onTurnOnNfc = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
        navigation = navigation,
    )
}

/**
 * A transit IC card: its balance, read from the card itself so it shows at
 * once and offline, then what's new on it. A card imported before, with
 * nothing lost since, imports its new entries in one tap with last time's
 * choices; otherwise the full review opens, as "Review first" does.
 */
@Composable
internal fun CardScreen(
    wallet: Wallet?,
    state: CardUiState,
    nfc: NfcState,
    actions: CardActions,
    onReview: () -> Unit,
    onTurnOnNfc: () -> Unit,
    navigation: ImportsNavigation,
) {
    val title = state.preview?.card?.let { stringResource(R.string.feature_imports_card_named, it) } ?: stringResource(R.string.feature_imports_ic_card)
    ImportPage(title, navigation) {
        if (wallet == null) return@ImportPage
        state.problem?.let { problem ->
            item {
                Text(
                    stringResource(if (problem == IcCardProblem.NotTransit) R.string.feature_imports_not_transit else R.string.feature_imports_lost),
                    color = WalletTheme.colors.attention,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item { Reader(state, nfc, actions, onTurnOnNfc) }
        val read = state.read
        read?.newest?.let { (balance, day) -> item { Balance(balance, day, wallet) } }
        if (read != null && state.preview == null && state.error == null) {
            item { Text(stringResource(R.string.feature_imports_checking), color = WalletTheme.colors.muted, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        state.error?.let { e ->
            item {
                Text(
                    stringResource(R.string.feature_imports_couldnt_check, e.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_imports_offline)),
                    color = WalletTheme.colors.attention,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        val preview = state.preview ?: return@ImportPage
        if (preview.new > 0) item { NewEntries(state, actions, onReview) }
        if (preview.new == 0 && state.result == null) {
            item { Text(stringResource(R.string.feature_imports_all_imported), modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        state.result?.let { result ->
            item { ImportSummary(result, navigation) }
            result.balance?.takeIf { it.difference != 0L }?.let { b ->
                item {
                    val format = rememberMoneyFormat(wallet.settings)
                    Text(
                        stringResource(R.string.feature_imports_card_off, wallet.account(b.accountId)?.name.orEmpty(), format.money(kotlin.math.abs(b.difference))),
                        color = WalletTheme.colors.attention,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.feature_imports_latest_entries),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
            )
        }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                val rows = preview.rows.asReversed() // the server's are oldest first; the newest shows first
                rows.forEachIndexed { i, r ->
                    Entry(r, wallet)
                    if (i < rows.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                }
            }
        }
    }
}

/** Reading a card: what to do, NFC off, or waiting for the card. */
@Composable
private fun Reader(state: CardUiState, nfc: NfcState, actions: CardActions, onTurnOnNfc: () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                nfc == NfcState.NoNfc -> Text(stringResource(R.string.feature_imports_no_nfc), color = WalletTheme.colors.muted)
                nfc == NfcState.Off -> {
                    Text(stringResource(R.string.feature_imports_nfc_off))
                    OutlinedButton(onClick = onTurnOnNfc) { Text(stringResource(R.string.feature_imports_turn_on_nfc)) }
                }
                state.waiting -> Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.feature_imports_hold_card), Modifier.weight(1f))
                    TextButton(onClick = { actions.waitForCard(false) }) { Text(stringResource(R.string.feature_imports_cancel)) }
                }
                else -> {
                    if (state.read == null) Text(stringResource(R.string.feature_imports_tap_card))
                    Button(
                        onClick = { actions.waitForCard(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                    ) { Text(stringResource(R.string.feature_imports_read_card)) }
                }
            }
        }
    }
}

@Composable
private fun Balance(balance: Long, day: java.time.LocalDate, wallet: Wallet) {
    val format = rememberMoneyFormat(wallet.settings)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.feature_imports_balance_on_card), color = WalletTheme.colors.muted)
        Text(format.money(balance), style = MaterialTheme.typography.displaySmall.figures())
        Text(stringResource(R.string.feature_imports_last_used, format.date(day)), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
    }
}

@Composable
private fun NewEntries(state: CardUiState, actions: CardActions, onReview: () -> Unit) {
    val preview = state.preview ?: return
    val before = preview.rows.any { it.status == ImportRowStatus.Imported }
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (before) {
                    pluralStringResource(R.plurals.feature_imports_new_since, preview.new, preview.new)
                } else {
                    pluralStringResource(R.plurals.feature_imports_not_imported, preview.new, preview.new)
                },
            )
            if (preview.gap) {
                Text(
                    stringResource(R.string.feature_imports_gap),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(WalletTheme.colors.attentionSoft, MaterialTheme.shapes.small).padding(8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (state.known) OutlinedButton(onClick = onReview, enabled = !state.busy) { Text(stringResource(R.string.feature_imports_review_first)) }
                Button(
                    onClick = if (state.known) actions::importNew else onReview,
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WalletTheme.colors.onAction)
                        Box(Modifier.size(8.dp))
                        Text(stringResource(R.string.feature_imports_importing))
                    } else {
                        Text(pluralStringResource(R.plurals.feature_imports_import_entries, preview.new, preview.new))
                    }
                }
            }
        }
    }
}

@Composable
private fun Entry(r: ImportRow, wallet: Wallet) {
    val format = rememberMoneyFormat(wallet.settings)
    val what = when {
        r.status == ImportRowStatus.Start -> stringResource(R.string.feature_imports_start_unknown)
        r.kind == TransactionKind.Transfer -> stringResource(R.string.feature_imports_top_up)
        else -> r.text
    }
    val status = when (r.status) {
        ImportRowStatus.New -> stringResource(R.string.feature_imports_status_new)
        ImportRowStatus.Imported -> stringResource(R.string.feature_imports_status_imported)
        ImportRowStatus.Matched -> stringResource(R.string.feature_imports_already_recorded)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(what, r.lines).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(format.date(r.date) + (r.time?.let { " $it" } ?: ""), status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (r.status == ImportRowStatus.New) WalletTheme.colors.attention else WalletTheme.colors.muted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (r.status != ImportRowStatus.Start) Text(format.money(r.amount, signed = true), style = MaterialTheme.typography.bodyMedium.figures())
            r.balance?.let { Text(format.money(it), style = MaterialTheme.typography.bodySmall.figures(), color = WalletTheme.colors.muted) }
        }
    }
}

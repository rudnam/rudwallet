package com.rudnam.wallet.feature.imports

import java.time.ZoneId
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.model.ImportBatch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.ImportSource

/** Where the import pages' taps lead. */
interface ImportsNavigation {
    fun back()

    /** The preview of a picked file (or, for the card, the card handed over). */
    fun review(source: ImportSource, uri: String?)

    /** The card page: reading a card, its balance and new entries. */
    fun card()

    /** A page by its website path: "settings?section=claude". */
    fun open(path: String)
}

/** A page of the import screens: its title, a way back, and its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportPage(
    title: String,
    navigation: ImportsNavigation,
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = navigation::back) { LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_imports_back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun ImportDestination(navigation: ImportsNavigation, snackbar: SnackbarHostState, viewModel: ImportHistoryViewModel = hiltViewModel()) {
    // Android's own file picker: CSV files, and whatever else a file manager calls them.
    // Which import the picker is for, kept if Android remakes the activity meanwhile.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = ImportSource.fromKey(picking)
        if (uri != null && source != null) navigation.review(source, uri.toString())
    }
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from an import (or its undo) lists it.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val res = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is HistoryEvent.Undone -> {
                    val r = snackbar.showSnackbar(
                        res.getQuantityString(R.plurals.feature_imports_batch_undone_snack, e.count, e.count),
                        actionLabel = res.getString(R.string.feature_imports_restore),
                        withDismissAction = true,
                    )
                    if (r == SnackbarResult.ActionPerformed) viewModel.restore(e.ids)
                }
                is HistoryEvent.Failed -> snackbar.showSnackbar(
                    if (e.message.isEmpty()) res.getString(R.string.feature_imports_offline) else res.getString(R.string.feature_imports_undo_failed, e.message),
                )
            }
        }
    }
    ImportScreen(
        onPick = { source ->
            picking = source.key
            pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
        },
        navigation = navigation,
        wallet = wallet,
        history = state,
        actions = viewModel,
    )
}

/**
 * Settings › Import: where records can come from (a file from PayPay, MUFG
 * or BudgetBakers, or a transit IC card), each one row with how to get it a
 * tap away, and the recent imports, each of which can be undone.
 */
@Composable
internal fun ImportScreen(
    onPick: (ImportSource) -> Unit,
    navigation: ImportsNavigation,
    wallet: Wallet? = null,
    history: ImportHistoryState = ImportHistoryState(),
    actions: HistoryActions? = null,
) {
    // Which source's how-to is open.
    var how by rememberSaveable { mutableStateOf<String?>(null) }
    ImportPage(stringResource(R.string.feature_imports_title), navigation) {
        item {
            Text(stringResource(R.string.feature_imports_sources_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                Column {
                    val sources = listOf(
                        Triple(ImportSource.PayPay, R.string.feature_imports_paypay_hint, R.string.feature_imports_paypay_how),
                        Triple(ImportSource.Mufg, R.string.feature_imports_mufg_hint, R.string.feature_imports_mufg_how),
                        Triple(ImportSource.Vpass, R.string.feature_imports_vpass_hint, R.string.feature_imports_vpass_how),
                        Triple(ImportSource.IcCard, R.string.feature_imports_ic_hint, R.string.feature_imports_ic_how),
                        Triple(ImportSource.BudgetBakers, R.string.feature_imports_budgetbakers_hint, R.string.feature_imports_budgetbakers_how),
                    )
                    sources.forEachIndexed { i, (source, hint, text) ->
                        if (i > 0) HorizontalDivider(color = WalletTheme.colors.border)
                        Source(
                            name = sourceLabel(source),
                            hint = stringResource(hint),
                            how = stringResource(text),
                            action = stringResource(if (source == ImportSource.IcCard) R.string.feature_imports_read_card else R.string.feature_imports_choose_file),
                            open = how == source.key,
                            onHow = { how = if (how == source.key) null else source.key },
                            onClick = { if (source == ImportSource.IcCard) navigation.card() else onPick(source) },
                        )
                    }
                }
            }
        }
        if (wallet != null && history.batches.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.feature_imports_recent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
                )
            }
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    Column {
                        history.batches.forEachIndexed { i, b ->
                            if (i > 0) HorizontalDivider(color = WalletTheme.colors.border)
                            BatchRow(b, wallet, undoing = history.undoing == b.id, onUndo = actions?.let { a -> { a.undo(b) } })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(source: ImportSource) = when (source) {
    ImportSource.PayPay -> "PayPay"
    ImportSource.Mufg -> "MUFG"
    ImportSource.BudgetBakers -> "BudgetBakers"
    ImportSource.IcCard -> stringResource(R.string.feature_imports_ic_card)
    ImportSource.Vpass -> "Vpass"
}

/** A source: its name and what to bring, tapped to pick the file (or read the card); how to get it opens below. */
@Composable
private fun Source(name: String, hint: String, how: String, action: String, open: Boolean, onHow: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = action, onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(hint, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            }
            IconButton(onClick = onHow) {
                LineIcon(WalletIcons.question, contentDescription = stringResource(R.string.feature_imports_how), tint = WalletTheme.colors.muted, size = 20.dp)
            }
            LineIcon(WalletIcons.chevron, contentDescription = null, tint = WalletTheme.colors.muted, size = 20.dp)
            Box(Modifier.size(12.dp))
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(how, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                OutlinedButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

/** A recent import: where from, the period, what it added and what it found already there, and Undo. */
@Composable
private fun BatchRow(b: ImportBatch, wallet: Wallet, undoing: Boolean, onUndo: (() -> Unit)?) {
    val format = rememberMoneyFormat(wallet.settings)
    val zone = ZoneId.systemDefault()
    val title = listOfNotNull(b.source?.let { sourceLabel(it) } ?: "", b.label.ifEmpty { null }?.let { "••$it" }).joinToString(" ")
    val period = if (b.from != null && b.to != null) format.dateRange(b.from!!.atZone(zone).toLocalDate(), b.to!!.atZone(zone).toLocalDate()) else null
    val already = b.duplicates + b.matched
    val what = buildList {
        add(pluralStringResource(R.plurals.feature_imports_batch_added, b.imported, b.imported))
        if (already > 0) add(pluralStringResource(R.plurals.feature_imports_batch_already, already, already))
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (b.undone) WalletTheme.colors.muted else WalletTheme.colors.text)
            Text(
                listOfNotNull(format.dateTime(b.createdAt), period).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = WalletTheme.colors.muted,
            )
            Text(what, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        }
        when {
            b.undone -> Text(stringResource(R.string.feature_imports_batch_undone), style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted, modifier = Modifier.padding(end = 8.dp))
            undoing -> CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            onUndo != null && b.remaining > 0 -> TextButton(onClick = onUndo) { Text(stringResource(R.string.feature_imports_undo_short)) }
        }
    }
}

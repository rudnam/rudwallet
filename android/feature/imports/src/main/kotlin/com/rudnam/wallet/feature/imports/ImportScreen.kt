package com.rudnam.wallet.feature.imports

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
internal fun ImportPage(title: String, navigation: ImportsNavigation, content: LazyListScope.() -> Unit) {
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
internal fun ImportDestination(navigation: ImportsNavigation) {
    // Android's own file picker: CSV files, and whatever else a file manager calls them.
    // Which import the picker is for, kept if Android remakes the activity meanwhile.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = ImportSource.fromKey(picking)
        if (uri != null && source != null) navigation.review(source, uri.toString())
    }
    ImportScreen(
        onPick = { source ->
            picking = source.key
            pick.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
        },
        navigation = navigation,
    )
}

/** Settings › Import: a file from PayPay, MUFG or BudgetBakers, or a transit IC card. */
@Composable
internal fun ImportScreen(onPick: (ImportSource) -> Unit, navigation: ImportsNavigation) {
    ImportPage(stringResource(R.string.feature_imports_title), navigation) {
        item { Source("PayPay", stringResource(R.string.feature_imports_paypay_how), stringResource(R.string.feature_imports_choose_file)) { onPick(ImportSource.PayPay) } }
        item { Source("MUFG", stringResource(R.string.feature_imports_mufg_how), stringResource(R.string.feature_imports_choose_file)) { onPick(ImportSource.Mufg) } }
        item { Source(stringResource(R.string.feature_imports_ic_card), stringResource(R.string.feature_imports_ic_how), stringResource(R.string.feature_imports_read_card), navigation::card) }
        item { Source("BudgetBakers", stringResource(R.string.feature_imports_budgetbakers_how), stringResource(R.string.feature_imports_choose_file)) { onPick(ImportSource.BudgetBakers) } }
    }
}

@Composable
private fun Source(name: String, how: String, action: String, onClick: () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(how, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

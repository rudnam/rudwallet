package com.rudnam.wallet.feature.wallet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.EmptyState
import com.rudnam.wallet.core.designsystem.component.PageLoading
import com.rudnam.wallet.core.designsystem.component.SectionHeader
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.WalletTopBar
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.designsystem.theme.figures
import com.rudnam.wallet.core.model.ChangelogEntry
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.core.ui.openNotificationAccess
import com.rudnam.wallet.core.ui.recordDays
import com.rudnam.wallet.core.ui.rememberMoneyFormat
import com.rudnam.wallet.core.ui.rememberNotificationAccess
import com.rudnam.wallet.core.ui.serverText
import java.time.Instant
import com.rudnam.wallet.core.designsystem.R as DesignSystemR

/** Where Wallet's taps lead: other pages, by the website's paths for them (the app's openPath). */
interface WalletNavigation {
    /** A page by its web path, e.g. "records", "account?id=3", "settings?section=notifications". */
    fun openPage(path: String)

    fun openRecord(record: Transaction)

    fun addRecord()

    /** Leaving a demo for an account of one's own: the sign-in page opens on creating one. */
    fun createAccount()

    /** Update on a copy from the Play Store: Play's own update screen. */
    fun updateFromPlay() {}
}

@Composable
internal fun WalletDestination(
    navigation: WalletNavigation,
    snackbar: SnackbarHostState,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    // Back from another page or app: what changed meanwhile (a payment, a record added elsewhere) shows.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    // Checked each time Wallet comes back into view, so coming back from Android's settings clears the card.
    val access = rememberNotificationAccess()
    LaunchedEffect(access) { access?.let(viewModel::onNotificationAccess) }

    val context = LocalContext.current
    val offline = stringResource(R.string.feature_wallet_offline)
    val failed = stringResource(R.string.feature_wallet_failed)
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        snackbar.showSnackbar(if (message == WalletMessage.Offline) offline else failed)
        viewModel.messageShown(message)
    }

    WalletScreen(
        state = state,
        navigation = navigation,
        onRefresh = { viewModel.refresh(userAsked = true) },
        onDismissSetup = viewModel::dismissSetup,
        onUpdateLater = viewModel::updateLater,
        onReorder = viewModel::reorder,
        onResend = viewModel::resendConfirmation,
        onLeaveDemo = {
            navigation.createAccount()
            viewModel.leaveDemo()
        },
        onTurnOnNotifications = { openNotificationAccess(context) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletScreen(
    state: WalletUiState,
    navigation: WalletNavigation,
    onRefresh: () -> Unit,
    onDismissSetup: () -> Unit,
    onUpdateLater: () -> Unit,
    onReorder: (List<Long>) -> Unit = {},
    onResend: () -> Unit = {},
    onLeaveDemo: () -> Unit = {},
    onTurnOnNotifications: () -> Unit = {},
    now: Instant = Instant.now(),
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        // The app's scaffold already keeps clear of the navigation bar.
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            val hideAmounts = com.rudnam.wallet.core.ui.LocalHideAmounts.current
            val toggleHide = com.rudnam.wallet.core.ui.LocalToggleHideAmounts.current
            WalletTopBar(
                title = { Text(stringResource(R.string.feature_wallet_title)) },
                actions = {
                    if (toggleHide != null) {
                        IconButton(onClick = toggleHide) {
                            LineIcon(
                                if (hideAmounts) WalletIcons.eyeOff else WalletIcons.eye,
                                contentDescription = stringResource(if (hideAmounts) R.string.feature_wallet_show_amounts else R.string.feature_wallet_hide_amounts),
                            )
                        }
                    }
                    IconButton(onClick = { navigation.openPage("records") }) {
                        LineIcon(WalletIcons.search, contentDescription = stringResource(R.string.feature_wallet_search))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = navigation::addRecord,
                containerColor = WalletTheme.colors.action,
                contentColor = WalletTheme.colors.onAction,
                shape = RoundedCornerShape(18.dp),
                elevation = FloatingActionButtonDefaults.loweredElevation(),
            ) {
                LineIcon(WalletIcons.plus, contentDescription = stringResource(R.string.feature_wallet_add_record), stroke = 2.2f)
            }
        },
    ) { padding ->
        val ready = state as? WalletUiState.Ready
        PullToRefreshBox(
            isRefreshing = ready?.refreshing == true,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (state) {
                WalletUiState.Loading -> PageLoading(Modifier.fillMaxSize())
                WalletUiState.Unavailable -> Unavailable(onRefresh)
                is WalletUiState.Ready -> Wallet(state, navigation, onDismissSetup, onUpdateLater, onReorder, onResend, onLeaveDemo, onTurnOnNotifications, now)
            }
        }
    }
}

@Composable
private fun Unavailable(onRetry: () -> Unit) {
    // A list, so pulling down to refresh works here too.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            EmptyState(
                stringResource(R.string.feature_wallet_unavailable),
                Modifier.padding(top = 40.dp),
                action = { OutlinedButton(onClick = onRetry) { Text(stringResource(DesignSystemR.string.designsystem_try_again)) } },
            )
        }
    }
}

@Composable
private fun Wallet(
    state: WalletUiState.Ready,
    navigation: WalletNavigation,
    onDismissSetup: () -> Unit,
    onUpdateLater: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    onResend: () -> Unit,
    onLeaveDemo: () -> Unit,
    onTurnOnNotifications: () -> Unit,
    now: Instant,
) {
    val format = rememberMoneyFormat(state.wallet.settings)
    val colors = WalletTheme.colors
    LazyColumn(
        Modifier.fillMaxSize(),
        // Room at the bottom for the plus, so it never covers the last record.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.me?.takeIf { it.demo }?.let {
            item(key = "demo", contentType = "notice") { DemoCard(onLeaveDemo) }
        }
        state.me?.takeIf { it.confirmEmail && !it.demo }?.let { me ->
            item(key = "confirm", contentType = "notice") { ConfirmEmailCard(me.email, state.confirmation, onResend) }
        }
        if (state.notificationAccessLost) {
            item(key = "notifications", contentType = "notice") { NotificationAccessCard(onTurnOnNotifications) }
        }
        state.update?.let { update ->
            item(key = "update", contentType = "update") {
                val uri = LocalUriHandler.current
                UpdateCard(
                    update.versionName,
                    update.changelog,
                    onInstall = { if (update.fromPlay) navigation.updateFromPlay() else uri.openUri(update.url) },
                    onLater = onUpdateLater,
                )
            }
        }
        state.setup?.let { setup ->
            item(key = "setup", contentType = "setup") {
                SetupCard(setup, state.wallet.settings.currency, navigation::openPage, onDismissSetup)
            }
        }
        item(key = "balance", contentType = "balance") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.feature_wallet_total_balance), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                Text(format.money(state.wallet.totalBalance), style = MaterialTheme.typography.displayMedium.figures())
                Spacer(Modifier.height(12.dp))
                CardStack(state.accounts, format, onOpen = { navigation.openPage("account?id=${it.id}") }, onReorder = onReorder)
            }
        }
        item(key = "month", contentType = "month") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(format.monthName(state.month))
                WalletPanel(Modifier.fillMaxWidth(), onClick = { navigation.openPage("stats") }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.feature_wallet_spent_this_month), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            Text(format.money(state.spent), style = MaterialTheme.typography.titleLarge.figures())
                        }
                        Text(headline(state.headline, format, state), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
            }
        }
        item(key = "latest", contentType = "heading") {
            SectionHeader(stringResource(R.string.feature_wallet_latest_records)) {
                TextButton(onClick = { navigation.openPage("records") }) {
                    Text(stringResource(R.string.feature_wallet_all_records), color = colors.muted)
                }
            }
        }
        if (state.latest.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                WalletPanel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        stringResource(R.string.feature_wallet_nothing_recorded),
                        action = { OutlinedButton(onClick = navigation::addRecord) { Text(stringResource(R.string.feature_wallet_add_a_record)) } },
                    )
                }
            }
        } else {
            recordDays(state.latest, state.wallet, format, navigation::openRecord, now)
        }
    }
}

@Composable
private fun headline(h: MonthHeadline, format: MoneyFormat, state: WalletUiState.Ready): String = when (h) {
    MonthHeadline.NothingSpent -> stringResource(R.string.feature_wallet_no_spending)
    is MonthHeadline.PerDay -> stringResource(R.string.feature_wallet_per_day, format.money(h.amount))
    is MonthHeadline.OverBudget -> stringResource(R.string.feature_wallet_over_budget, format.money(h.amount))
    is MonthHeadline.SpentSoFar -> stringResource(R.string.feature_wallet_spent_so_far, format.money(h.amount), format.monthName(state.month))
}


/** How many of the update's notes show before "Show all". */
private const val COLLAPSED_CHANGELOG_ITEMS = 3

@Composable
private fun UpdateCard(versionName: String, changelog: List<ChangelogEntry>, onInstall: () -> Unit, onLater: () -> Unit) {
    val japanese = LocalConfiguration.current.locales[0]?.language == "ja"
    val notes = remember(changelog, japanese) { changelog.flatMap { it.notesFor(japanese) } }
    var expanded by remember(versionName) { mutableStateOf(false) }
    WalletPanel(Modifier.fillMaxWidth()) {
      Column {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(WalletIcons.line(WalletIcons.download), contentDescription = null, Modifier.size(20.dp), tint = WalletTheme.colors.muted)
            Text(
                if (versionName.isNotEmpty()) stringResource(R.string.feature_wallet_update, versionName) else stringResource(R.string.feature_wallet_update_unnamed),
                Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onLater) { Text(stringResource(R.string.feature_wallet_update_later), color = WalletTheme.colors.muted) }
            TextButton(onClick = onInstall) { Text(stringResource(R.string.feature_wallet_update_install)) }
        }
        if (notes.isNotEmpty()) {
            // Lined up under the message, past the icon.
            Column(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val visible = if (expanded) notes else notes.take(COLLAPSED_CHANGELOG_ITEMS)
                for (note in visible) {
                    Text("• $note", style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted)
                }
                if (notes.size > COLLAPSED_CHANGELOG_ITEMS) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (expanded) R.string.feature_wallet_update_show_less else R.string.feature_wallet_update_show_all))
                    }
                }
            }
        }
      }
    }
}

/** Notification access went off (a reinstall, say): payments aren't being recorded until it's back on. */
@Composable
private fun NotificationAccessCard(onTurnOn: () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(WalletIcons.warning, contentDescription = null, tint = WalletTheme.colors.danger, size = 20.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.feature_wallet_notifications_off), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.feature_wallet_notifications_off_detail), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            }
            TextButton(onClick = onTurnOn) { Text(stringResource(R.string.feature_wallet_notifications_turn_on)) }
        }
    }
}

/** In a demo: what this is, and the way to an account of one's own. */
@Composable
private fun DemoCard(onCreateAccount: () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.background(WalletTheme.colors.sunken).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineIcon(WalletIcons.spark, contentDescription = null, size = 20.dp)
                Text(stringResource(R.string.feature_wallet_demo), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.feature_wallet_demo_detail), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
            Button(
                onClick = onCreateAccount,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) { Text(stringResource(R.string.feature_wallet_demo_create)) }
        }
    }
}

/** A reminder to open the link we emailed, with a way to send it again. */
@Composable
private fun ConfirmEmailCard(email: String, state: Confirmation, onResend: () -> Unit) {
    WalletPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(WalletIcons.mail, contentDescription = null, tint = WalletTheme.colors.muted, size = 20.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    stringResource(if (state == Confirmation.Sent) R.string.feature_wallet_confirm_sent else R.string.feature_wallet_confirm, email),
                    style = MaterialTheme.typography.bodyMedium,
                )
                (state as? Confirmation.Failed)?.let {
                    Text(
                        it.message.ifEmpty { null }?.let { m -> serverText(m) } ?: stringResource(R.string.feature_wallet_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = WalletTheme.colors.danger,
                    )
                }
            }
            if (state != Confirmation.Sent) {
                TextButton(onClick = onResend, enabled = state != Confirmation.Sending) { Text(stringResource(R.string.feature_wallet_confirm_again)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(setup: SetupChecklist, currency: String, openPage: (String) -> Unit, onHide: () -> Unit) {
    val colors = WalletTheme.colors
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.feature_wallet_setup_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.feature_wallet_setup_progress, setup.done, setup.total), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                TextButton(onClick = onHide) { Text(stringResource(R.string.feature_wallet_setup_hide)) }
            }
            SetupStep(setup.accountsAdded, R.string.feature_wallet_setup_accounts, R.string.feature_wallet_setup_accounts_detail, R.string.feature_wallet_setup_accounts_action) {
                openPage("settings?section=accounts")
            }
            SetupStep(setup.notificationsOn, R.string.feature_wallet_setup_notifications, R.string.feature_wallet_setup_notifications_detail, R.string.feature_wallet_setup_notifications_action) {
                openPage("settings?section=notifications")
            }
            SetupStep(setup.budgetSet, R.string.feature_wallet_setup_budget, R.string.feature_wallet_setup_budget_detail, R.string.feature_wallet_setup_budget_action) {
                openPage("budgets")
            }
            AnimatedVisibility(setup.noRecordsYet) {
                // Before the first record: the currency can still change, and history can come from another app.
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                    Text(stringResource(R.string.feature_wallet_setup_currency, currency), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    FlowRow {
                        TextButton(onClick = { openPage("settings?section=general") }) { Text(stringResource(R.string.feature_wallet_setup_change_currency)) }
                        TextButton(onClick = { openPage("settings?section=import") }) { Text(stringResource(R.string.feature_wallet_setup_import)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: Int, detail: Int, action: Int, onAction: () -> Unit) {
    val colors = WalletTheme.colors
    val doneLabel = stringResource(R.string.feature_wallet_setup_done)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (done) LineIcon(WalletIcons.check, contentDescription = doneLabel, tint = colors.positive, size = 18.dp, stroke = 2.4f)
            else LineIcon(WalletIcons.dot, contentDescription = null, tint = colors.muted, size = 18.dp, stroke = 6f)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = if (done) colors.muted else colors.text)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (!done) TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}

@Preview
@Composable
private fun WalletScreenPreview() {
    WalletTheme {
        WalletScreen(
            state = WalletUiState.Unavailable,
            navigation = object : WalletNavigation {
        override fun createAccount() {}
                override fun openPage(path: String) {}
                override fun openRecord(record: Transaction) {}
                override fun addRecord() {}
            },
            onRefresh = {},
            onDismissSetup = {},
            onUpdateLater = {},
        )
    }
}

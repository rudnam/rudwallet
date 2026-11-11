package com.rudnam.wallet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rudnam.wallet.core.designsystem.theme.WalletMotion
import com.rudnam.wallet.core.ui.LocalSharedTransitionScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import com.rudnam.wallet.R
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.data.repository.RecordEvent
import com.rudnam.wallet.core.data.repository.SaveResult
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.WalletSettings
import com.rudnam.wallet.core.ui.MoneyFormat
import com.rudnam.wallet.feature.account.AccountNavigation
import com.rudnam.wallet.feature.account.navigation.accountScreen
import com.rudnam.wallet.feature.activity.ActivityNavigation
import com.rudnam.wallet.feature.activity.navigation.DuplicatesRoute
import com.rudnam.wallet.feature.activity.navigation.ActivityRoute
import com.rudnam.wallet.feature.activity.navigation.activityScreen
import com.rudnam.wallet.core.model.ImportSource
import com.rudnam.wallet.feature.imports.ImportsNavigation
import com.rudnam.wallet.feature.imports.navigation.CardRoute
import com.rudnam.wallet.feature.imports.navigation.ImportReviewRoute
import com.rudnam.wallet.feature.imports.navigation.importsScreens
import com.rudnam.wallet.feature.insights.InsightsNavigation
import com.rudnam.wallet.feature.signin.navigation.signInScreen
import com.rudnam.wallet.feature.insights.navigation.BudgetsRoute
import com.rudnam.wallet.feature.insights.navigation.insightsScreen
import com.rudnam.wallet.feature.settings.SettingsNavigation
import com.rudnam.wallet.feature.settings.navigation.settingsScreen
import com.rudnam.wallet.core.data.repository.SignOutReason
import com.rudnam.wallet.feature.record.navigation.RecordRoute
import com.rudnam.wallet.feature.record.navigation.recordSheet
import com.rudnam.wallet.feature.wallet.WalletNavigation
import com.rudnam.wallet.feature.wallet.navigation.WalletRoute
import com.rudnam.wallet.feature.wallet.navigation.walletScreen
import com.rudnam.wallet.navigation.TopLevelDestination
import com.rudnam.wallet.navigation.enter
import com.rudnam.wallet.navigation.exit
import com.rudnam.wallet.navigation.popEnter
import com.rudnam.wallet.navigation.popExit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.YearMonth
import com.rudnam.wallet.core.ui.ServerText

/** The app: the four tabs along the bottom, and the pages above them, each a destination of the NavHost. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RudWalletApp(
    signedIn: Boolean,
    reviewCount: Int,
    links: Flow<String>,
    recordEvents: Flow<RecordEvent>,
    settings: WalletSettings?,
    onUndo: suspend (RecordEvent) -> SaveResult,
    signOuts: Flow<SignOutReason> = emptyFlow(),
    appState: RudWalletAppState = rememberRudWalletAppState(),
) {
    val snackbar = remember { SnackbarHostState() }
    RecordMessages(recordEvents, settings, snackbar, onUndo)
    val tab = appState.currentTab

    // The app follows the session: the sign-in page without one, Wallet with one.
    LaunchedEffect(signedIn) {
        if (!signedIn) appState.showSignIn() else if (appState.onSignIn) appState.showWallet()
    }
    // An account deleted in Settings: the sign-in page says so.
    LaunchedEffect(signOuts) { signOuts.collect { if (it == SignOutReason.Deleted) appState.showSignIn(deleted = true) } }
    // Links into the app (the widgets: <package>://open/<page>).
    LaunchedEffect(links) { links.collect { appState.openPath(it) } }

    Scaffold(
        containerColor = WalletTheme.colors.ground,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { AnimatedTabBar(tab, reviewCount, appState::navigateToTab) },
    ) { padding ->
        // Pages can share elements as they change (an account's card grows into its page).
        SharedTransitionLayout(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                NavHost(
                    appState.navController,
                    startDestination = WalletRoute,
                    enterTransition = { enter() },
                    exitTransition = { exit() },
                    popEnterTransition = { popEnter() },
                    popExitTransition = { popExit() },
                ) {
                    walletScreen(
                        navigation = object : WalletNavigation {
                            override fun openPage(path: String) = appState.openPath(path)
                            override fun openRecord(record: Transaction) = appState.navController.navigate(RecordRoute(record.id))
                            override fun addRecord() = appState.navController.navigate(RecordRoute())
                            override fun createAccount() {
                                appState.signUpNext = true
                            }
                        },
                        snackbar = snackbar,
                    )
                    activityScreen(
                        navigation = object : ActivityNavigation {
                            override fun openRecord(record: Transaction) = appState.navController.navigate(RecordRoute(record.id))
                            override fun addRecord() = appState.navController.navigate(RecordRoute())
                            override fun openDuplicates() = appState.navController.navigate(DuplicatesRoute)
                            override fun back() {
                                appState.navController.popBackStack()
                            }
                        },
                        snackbar = snackbar,
                    )
                    accountScreen(
                        navigation = object : AccountNavigation {
                            override fun back() {
                                appState.navController.popBackStack()
                            }
                            override fun editAccounts() = appState.openPath("settings?section=accounts")
                            override fun openRecord(record: Transaction) = appState.navController.navigate(RecordRoute(record.id))
                        },
                        snackbar = snackbar,
                    )
                    signInScreen()
                    insightsScreen(
                        navigation = object : InsightsNavigation {
                            override fun openBudgets() = appState.navController.navigate(BudgetsRoute)
                            override fun openRecords(month: YearMonth, categoryId: Long, accountId: Long?) =
                                appState.navController.navigate(ActivityRoute(month = month.toString(), account = accountId, category = categoryId))
                            override fun back() {
                                appState.navController.popBackStack()
                            }
                        },
                        snackbar = snackbar,
                    )
                    settingsScreen(
                        navigation = object : SettingsNavigation {
                            override fun open(path: String) = appState.openPath(path)
                            override fun back() {
                                appState.navController.popBackStack()
                            }
                        },
                        snackbar = snackbar,
                    )
                    importsScreens(
                        navigation = object : ImportsNavigation {
                            override fun back() {
                                appState.navController.popBackStack()
                            }
                            override fun review(source: ImportSource, uri: String?) = appState.navController.navigate(ImportReviewRoute(source.key, uri))
                            override fun card() = appState.navController.navigate(CardRoute)
                            override fun open(path: String) = appState.openPath(path)
                        },
                        snackbar = snackbar,
                    )
                    recordSheet(onDone = { appState.navController.popBackStack() })
                }
            }
        }
    }
}

/**
 * The tab bar, on the tabs only: it slides away as a page opens on top of a tab, and back as
 * the page closes, in step with the pages (rather than vanishing in one frame).
 */
@Composable
private fun AnimatedTabBar(tab: TopLevelDestination?, reviewCount: Int, onSelect: (TopLevelDestination) -> Unit) {
    // While it slides away there's no tab any more: it keeps showing the last one.
    val last = remember { object { var tab = TopLevelDestination.Wallet } }
    if (tab != null) last.tab = tab
    val spec = tween<IntSize>(WalletMotion.PAGE, easing = WalletMotion.EmphasizedDecelerate)
    val slide = tween<IntOffset>(WalletMotion.PAGE, easing = WalletMotion.EmphasizedDecelerate)
    AnimatedVisibility(
        visible = tab != null,
        enter = expandVertically(spec, expandFrom = Alignment.Top) + slideInVertically(slide) { it },
        exit = shrinkVertically(spec, shrinkTowards = Alignment.Top) + slideOutVertically(slide) { it },
    ) {
        TabBar(last.tab, reviewCount, onSelect)
    }
}


@Composable
private fun TabBar(current: TopLevelDestination, reviewCount: Int, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar(containerColor = WalletTheme.colors.ground) {
        for (tab in TopLevelDestination.entries) {
            val selected = tab == current
            val label = stringResource(tab.label)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = {
                    if (tab == TopLevelDestination.Activity && reviewCount > 0) {
                        val badge = pluralStringResource(R.plurals.to_review, reviewCount, reviewCount)
                        BadgedBox(badge = {
                            Badge(Modifier.semantics { contentDescription = badge }, containerColor = WalletTheme.colors.attention, contentColor = WalletTheme.colors.ground) {
                                Text(reviewCount.toString())
                            }
                        }) { LineIcon(tab.icon, contentDescription = null) }
                    } else {
                        LineIcon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * What the record sheet did, said once it has closed: in the snackbar, with
 * Undo for a change or a delete (as on the website).
 */
@Composable
private fun RecordMessages(events: Flow<RecordEvent>, settings: WalletSettings?, snackbar: SnackbarHostState, onUndo: suspend (RecordEvent) -> SaveResult) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val current by rememberUpdatedState(settings)
    LaunchedEffect(events) {
        events.collect { event ->
            val money = current?.let { MoneyFormat(locale, it) }
            val text = when (event) {
                is RecordEvent.Added -> resources.getString(R.string.message_added, money?.money(event.amount) ?: event.amount.toString())
                RecordEvent.Queued -> resources.getString(R.string.message_queued)
                RecordEvent.Confirmed -> resources.getString(R.string.message_confirmed)
                is RecordEvent.Changed -> resources.getString(R.string.message_saved)
                is RecordEvent.Deleted -> resources.getString(R.string.message_deleted)
            }
            val undoable = event is RecordEvent.Changed || (event is RecordEvent.Deleted && event.restorable)
            val result = snackbar.showSnackbar(text, actionLabel = if (undoable) resources.getString(R.string.message_undo) else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                val after = when (val r = onUndo(event)) {
                    is SaveResult.Saved, SaveResult.Done, SaveResult.Queued ->
                        resources.getString(if (event is RecordEvent.Deleted) R.string.message_restored else R.string.message_change_undone)
                    is SaveResult.Refused -> resources.getString(R.string.message_undo_failed, ServerText.translate(r.message, resources.configuration.locales[0]))
                    else -> resources.getString(R.string.message_undo_failed, resources.getString(R.string.message_offline))
                }
                snackbar.showSnackbar(after)
            }
        }
    }
}

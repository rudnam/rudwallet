package com.rudnam.wallet.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.McpToken
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.NotificationStatus
import com.rudnam.wallet.core.model.PaymentNotification
import com.rudnam.wallet.core.model.Rules
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.ui.CategoryIcon
import com.rudnam.wallet.core.ui.ServerText
import com.rudnam.wallet.core.ui.serverText
import java.time.Instant

/** The snackbar for a page's messages. */
@Composable
internal fun PageMessages(messages: List<PageMessage>, shown: (PageMessage) -> Unit, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        shown(message)
        snackbar.showSnackbar(
            when (message) {
                is PageMessage.Said -> resources.getString(message.text, message.arg)
                PageMessage.Offline -> resources.getString(R.string.feature_settings_offline).also { haptics.error() }
                is PageMessage.Refused -> ServerText.translate(message.message, resources.configuration.locales[0]).also { haptics.error() }
            },
        )
    }
}

/** A section title inside a page. */
@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted, modifier = modifier.padding(horizontal = 4.dp))
}

/** Loading, or why it couldn't load (with Try again), for a [Loadable] that isn't ready. */
@Composable
private fun NotReady(state: Loadable<*>, onRetry: (() -> Unit)? = null) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Loadable.Offline, is Loadable.Failed -> Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val text = when (state) {
                is Loadable.Failed -> state.message.ifEmpty { null }?.let { serverText(it) } ?: stringResource(R.string.feature_settings_failed_load)
                else -> stringResource(R.string.feature_settings_offline)
            }
            Text(text, color = WalletTheme.colors.attention, style = MaterialTheme.typography.bodyMedium)
            onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.feature_settings_try_again)) } }
        }
        is Loadable.Ready<*> -> Unit
    }
}

// ---------- Shops and cards ----------

@Composable
internal fun RulesDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: RulesViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    PageMessages(messages, viewModel::messageShown, snackbar)
    RulesScreen(wallet, rules, viewModel, navigation)
}

/** What confirming payments taught the app (each shop's category, each card's account), to check, correct or forget. */
@Composable
internal fun RulesScreen(wallet: Wallet?, rules: Loadable<Rules>, actions: RulesActions, navigation: SettingsNavigation) {
    SettingsPage(stringResource(R.string.feature_settings_rules), navigation) {
        item { Intro(stringResource(R.string.feature_settings_rules_intro)) }
        val ready = (rules as? Loadable.Ready)?.value
        if (wallet == null || ready == null) {
            item { NotReady(rules, actions::reload) }
            return@SettingsPage
        }
        item { Heading(stringResource(R.string.feature_settings_shops)) }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                if (ready.payees.isEmpty()) {
                    Muted(stringResource(R.string.feature_settings_no_shops), Modifier.padding(12.dp))
                }
                ready.payees.forEachIndexed { i, rule ->
                    RuleRow(
                        title = rule.payee,
                        records = rule.records,
                        onForget = { actions.forgetShop(rule.key, rule.payee) },
                    ) {
                        CategoryChoice(wallet.categoriesInOrder(), rule.categoryId, stringResource(R.string.feature_settings_category_for, rule.payee)) {
                            actions.setShop(rule.key, rule.payee, it)
                        }
                    }
                    if (i < ready.payees.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                }
            }
        }
        item { AddShop(wallet.categoriesInOrder(), actions::addShop) }
        item { Heading(stringResource(R.string.feature_settings_cards)) }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                if (ready.cards.isEmpty()) {
                    Muted(stringResource(R.string.feature_settings_no_cards), Modifier.padding(12.dp))
                }
                ready.cards.forEachIndexed { i, rule ->
                    RuleRow(title = rule.source, records = rule.records, onForget = { actions.forgetCard(rule.source) }) {
                        AccountChoice(wallet, rule.accountId, stringResource(R.string.feature_settings_account_for, rule.source)) { actions.setCard(rule.source, it) }
                    }
                    if (i < ready.cards.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                }
            }
        }
        item { Muted(stringResource(R.string.feature_settings_forget_card_note)) }
    }
}

/** A shop or a card: its name and records, what it's filed under, and Forget. */
@Composable
private fun RuleRow(title: String, records: Int, onForget: () -> Unit, choice: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.feature_settings_record_count, records, records), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        }
        choice()
        IconButton(onClick = onForget) {
            LineIcon(WalletIcons.close, contentDescription = stringResource(R.string.feature_settings_forget_named, title), tint = WalletTheme.colors.muted, size = 20.dp)
        }
    }
}

/** A compact menu of categories (expenses, then income), showing the one chosen. */
@Composable
private fun CategoryChoice(categories: List<Category>, chosen: Long?, label: String, onChoose: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = categories.firstOrNull { it.id == chosen }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 168.dp).semantics { contentDescription = "$label: ${current?.name.orEmpty()}" }) {
            current?.let { CategoryIcon(it, Modifier.size(24.dp)) }
            Text(
                current?.name ?: stringResource(R.string.feature_settings_pick_category),
                Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = WalletTheme.colors.text,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (kind in CategoryKind.entries) {
                Text(
                    stringResource(if (kind == CategoryKind.Expense) R.string.feature_settings_expenses else R.string.feature_settings_income),
                    style = MaterialTheme.typography.labelMedium,
                    color = WalletTheme.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                for (c in categories.filter { it.kind == kind }) {
                    // In order: a subcategory follows its main category, indented.
                    val indent = if (c.parentId != null && categories.any { it.id == c.parentId }) 20.dp else 0.dp
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        leadingIcon = { CategoryIcon(c, Modifier.padding(start = indent).size(28.dp)) },
                        onClick = {
                            open = false
                            onChoose(c.id)
                        },
                    )
                }
            }
        }
    }
}

/** A compact menu of the accounts in use (and the chosen one, if it's archived). */
@Composable
private fun AccountChoice(wallet: Wallet, chosen: Long?, label: String, onChoose: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = wallet.account(chosen)
    val options = wallet.activeAccounts + listOfNotNull(current?.takeIf { it.archived })
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 168.dp).semantics { contentDescription = "$label: ${current?.name.orEmpty()}" }) {
            current?.let { AccountMark(it, Modifier.size(24.dp)) }
            Text(
                current?.name ?: stringResource(R.string.feature_settings_archived_account),
                Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = WalletTheme.colors.text,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in options) {
                DropdownMenuItem(
                    text = { Text(a.name) },
                    leadingIcon = { AccountMark(a, Modifier.size(28.dp)) },
                    onClick = {
                        open = false
                        onChoose(a.id)
                    },
                )
            }
        }
    }
}

/** A shop added by hand, before its first payment: its name and category. */
@Composable
private fun AddShop(categories: List<Category>, onAdd: (String, Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<Long?>(null) }
    val add = {
        category?.let {
            onAdd(name, it)
            name = ""
            category = null
        }
    }
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_settings_add_shop), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.feature_settings_shop_name)) },
                placeholder = { Text(stringResource(R.string.feature_settings_shop_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CategoryChoice(categories, category, stringResource(R.string.feature_settings_category)) { category = it }
                }
                Button(
                    onClick = { add() },
                    enabled = name.isNotBlank() && category != null,
                    colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                ) { Text(stringResource(R.string.feature_settings_add)) }
            }
        }
    }
}

// ---------- Payment notifications ----------

/** Whether RudWallet may read notifications, checked again each time the page comes back (from Android's settings, say). */
@Composable
private fun rememberNotificationAccess(): Boolean? {
    val context = LocalContext.current
    var access by remember { mutableStateOf<Boolean?>(null) }
    LifecycleResumeEffect(Unit) {
        access = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        onPauseOrDispose {}
    }
    return access
}

/** Android's notification access page, at RudWallet's own switch where it can. */
private fun openNotificationAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val listener = ComponentName(context.packageName, "com.rudnam.wallet.notify.PaymentListener")
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

@Composable
internal fun NotificationsDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: NotificationsViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val autoConfirm by viewModel.autoConfirm.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PageMessages(messages, viewModel::messageShown, snackbar)
    NotificationsScreen(wallet, rememberNotificationAccess(), autoConfirm, recent, viewModel, onOpenAccess = { openNotificationAccess(context) }, navigation = navigation)
}

/**
 * The app reads PayPay's and Google Wallet's payment notifications once
 * it's allowed to, and each payment becomes a record. Where they go, and
 * what arrived, are here too.
 */
@Composable
internal fun NotificationsScreen(
    wallet: Wallet?,
    access: Boolean?,
    autoConfirm: Boolean?,
    recent: Loadable<List<PaymentNotification>>,
    actions: NotificationsActions,
    onOpenAccess: () -> Unit,
    navigation: SettingsNavigation,
) {
    SettingsPage(stringResource(R.string.feature_settings_notifications), navigation) {
        item { Intro(stringResource(R.string.feature_settings_notifications_intro)) }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (access == null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(10.dp).background(if (access) WalletTheme.colors.positive else WalletTheme.colors.attention, RoundedCornerShape(5.dp)))
                            Text(stringResource(if (access) R.string.feature_settings_access_on else R.string.feature_settings_access_off), Modifier.weight(1f))
                        }
                        if (access) {
                            OutlinedButton(onClick = onOpenAccess) { Text(stringResource(R.string.feature_settings_turn_off)) }
                        } else {
                            Button(
                                onClick = onOpenAccess,
                                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
                            ) { Text(stringResource(R.string.feature_settings_allow_access)) }
                            Muted(stringResource(R.string.feature_settings_access_help), Modifier.padding(0.dp))
                        }
                    }
                }
            }
        }
        item { Muted(stringResource(R.string.feature_settings_only_payments)) }
        if (wallet != null) {
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    val on = autoConfirm ?: wallet.settings.autoConfirm
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = on, role = Role.Switch, onValueChange = actions::setAutoConfirm)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.feature_settings_confirm_known))
                            Text(stringResource(R.string.feature_settings_confirm_known_detail), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                        }
                        Switch(checked = on, onCheckedChange = null)
                    }
                    HorizontalDivider(color = WalletTheme.colors.border)
                    DefaultAccount(wallet, actions::setDefaultAccount)
                }
            }
            item { Muted(stringResource(R.string.feature_settings_card_rules_note), Modifier.clickable { navigation.open("settings?section=rules") }) }
        }
        val list = (recent as? Loadable.Ready)?.value
        if (!list.isNullOrEmpty()) {
            item { Heading(stringResource(R.string.feature_settings_recent_notifications)) }
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    val shown = list.take(10)
                    shown.forEachIndexed { i, n ->
                        NotificationRow(n)
                        if (i < shown.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                    }
                }
            }
        }
    }
}

/** Where payments go: an account, or the first one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultAccount(wallet: Wallet, onChoose: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val first = stringResource(R.string.feature_settings_first_account)
    val current = wallet.account(wallet.settings.defaultAccountId)?.takeIf { !it.archived }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = current?.name ?: first,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.feature_settings_payments_go_to)) },
            leadingIcon = current?.let { { AccountMark(it, Modifier.size(28.dp)) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(first) }, onClick = {
                open = false
                onChoose(null)
            })
            for (a in wallet.activeAccounts) {
                DropdownMenuItem(text = { Text(a.name) }, leadingIcon = { AccountMark(a, Modifier.size(28.dp)) }, onClick = {
                    open = false
                    onChoose(a.id)
                })
            }
        }
    }
}

/** A moment as a list writes it, in the screen's language: "Nov 12, 18:05". */
@Composable
private fun shortDateTime(at: Instant, skeleton: String = "MMMdjmm"): String {
    val locale = LocalResources.current.configuration.locales[0]
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return at.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

@Composable
private fun NotificationRow(n: PaymentNotification) {
    val (label, tint) = when (n.status) {
        NotificationStatus.Payment -> R.string.feature_settings_status_payment to WalletTheme.colors.positive
        NotificationStatus.Matched -> R.string.feature_settings_status_matched to WalletTheme.colors.muted
        NotificationStatus.Ignored -> R.string.feature_settings_status_ignored to WalletTheme.colors.muted
        NotificationStatus.Unparsed -> R.string.feature_settings_status_unparsed to WalletTheme.colors.attention
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(n.title.ifEmpty { n.app } + if (n.text.isNotEmpty()) " · ${n.text}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (n.detail.isNotEmpty() && n.status != NotificationStatus.Matched) " · " + serverText(n.detail) else ""
            Text(
                shortDateTime(n.postedAt) + " · " + n.app + detail,
                style = MaterialTheme.typography.bodySmall,
                color = WalletTheme.colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.background(tint.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---------- Home screen widgets ----------

private data class WidgetInfo(val name: Int, val about: Int, val receiver: String)

private val widgets = listOf(
    WidgetInfo(R.string.feature_settings_this_month, R.string.feature_settings_this_month_detail, "com.rudnam.wallet.widget.MonthWidget"),
    WidgetInfo(R.string.feature_settings_budgets, R.string.feature_settings_budgets_detail, "com.rudnam.wallet.widget.BudgetsWidget"),
    WidgetInfo(R.string.feature_settings_new_record, R.string.feature_settings_new_record_detail, "com.rudnam.wallet.widget.NewRecordWidget"),
)

@Composable
internal fun WidgetsDestination(navigation: SettingsNavigation) {
    val context = LocalContext.current
    val manager = AppWidgetManager.getInstance(context)
    // Android 8 and up can put one on the home screen from here, if the launcher allows it.
    val canPin = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported
    WidgetsScreen(
        canPin = canPin,
        onPin = { receiver ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.requestPinAppWidget(ComponentName(context.packageName, receiver), null, null)
        },
        navigation = navigation,
    )
}

/** What the widgets show, and putting one on the home screen. */
@Composable
internal fun WidgetsScreen(canPin: Boolean, onPin: (String) -> Unit, navigation: SettingsNavigation) {
    SettingsPage(stringResource(R.string.feature_settings_widgets), navigation) {
        item { Intro(stringResource(R.string.feature_settings_widgets_intro) + " " + stringResource(R.string.feature_settings_widgets_how)) }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                widgets.forEachIndexed { i, w ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(stringResource(w.name))
                            Text(stringResource(w.about), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
                        }
                        if (canPin) {
                            val name = stringResource(w.name)
                            val add = stringResource(R.string.feature_settings_add_to_home)
                            IconButton(onClick = { onPin(w.receiver) }, modifier = Modifier.semantics { contentDescription = "$add: $name" }) {
                                LineIcon(WalletIcons.plus, contentDescription = null)
                            }
                        }
                    }
                    if (i < widgets.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                }
            }
        }
        item { Muted(stringResource(R.string.feature_settings_widgets_note)) }
    }
}

// ---------- Claude ----------

/** Copies [text]; a token is marked sensitive, so Android doesn't show it in its copy preview. */
private fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("RudWallet", text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
internal fun ClaudeDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: ClaudeViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    PageMessages(messages, viewModel::messageShown, snackbar)
    var copiedNote by remember { mutableIntStateOf(0) }
    LaunchedEffect(copiedNote) {
        // Android 13 and up says so itself.
        if (copiedNote > 0 && Build.VERSION.SDK_INT < 33) snackbar.showSnackbar(resources.getString(R.string.feature_settings_copied))
    }
    ClaudeScreen(me, state, viewModel, onCopy = { text, sensitive ->
        copy(context, text, sensitive)
        copiedNote++
    }, navigation = navigation)
}

/** Connections that let Claude (the app, Claude Desktop, Claude Code) reach the wallet, to sort records and answer questions. */
@Composable
internal fun ClaudeScreen(me: Me?, state: ClaudeUiState, actions: ClaudeActions, onCopy: (String, Boolean) -> Unit, navigation: SettingsNavigation) {
    SettingsPage(stringResource(R.string.feature_settings_claude), navigation) {
        if (me?.demo == true) {
            // A demo can't make tokens: it would reach outside the app.
            item { Intro(stringResource(R.string.feature_settings_claude_demo_intro)) }
            item { Text(stringResource(R.string.feature_settings_claude_demo), color = WalletTheme.colors.attention, modifier = Modifier.padding(horizontal = 4.dp)) }
            return@SettingsPage
        }
        item { Intro(stringResource(R.string.feature_settings_claude_intro)) }
        item {
            WalletPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.feature_settings_claude_app), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.feature_settings_claude_address), style = MaterialTheme.typography.labelMedium, color = WalletTheme.colors.muted)
                    CopyRow(state.address, sensitive = false, onCopy)
                }
            }
        }
        state.fresh?.token?.let { token ->
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.background(WalletTheme.colors.attentionSoft).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.feature_settings_copy_token), style = MaterialTheme.typography.titleSmall)
                        CopyRow(token, sensitive = true, onCopy)
                    }
                }
            }
        }
        item { NewConnection(state.busy, actions::create) }
        item { HowToConnect(state, onCopy) }
        val tokens = (state.tokens as? Loadable.Ready)?.value
        if (!tokens.isNullOrEmpty()) {
            item { Heading(stringResource(R.string.feature_settings_connections)) }
            item {
                WalletPanel(Modifier.fillMaxWidth()) {
                    tokens.forEachIndexed { i, t ->
                        TokenRow(t, onDisconnect = { actions.askDisconnect(t) })
                        if (i < tokens.lastIndex) HorizontalDivider(color = WalletTheme.colors.border)
                    }
                }
            }
        } else if (tokens == null) {
            item { NotReady(state.tokens) }
        }
    }
    state.disconnecting?.let { t ->
        AlertDialog(
            onDismissRequest = { actions.askDisconnect(null) },
            text = { Text(stringResource(R.string.feature_settings_disconnect_question, t.name)) },
            confirmButton = { TextButton(onClick = actions::disconnect) { Text(stringResource(R.string.feature_settings_disconnect), color = WalletTheme.colors.danger) } },
            dismissButton = { TextButton(onClick = { actions.askDisconnect(null) }) { Text(stringResource(R.string.feature_settings_cancel)) } },
        )
    }
}

/** Text to copy, in a monospaced box that scrolls sideways, with a Copy button. */
@Composable
private fun CopyRow(text: String, sensitive: Boolean, onCopy: (String, Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(WalletTheme.colors.sunken, MaterialTheme.shapes.small).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        TextButton(onClick = { onCopy(text, sensitive) }) { Text(stringResource(R.string.feature_settings_copy)) }
    }
}

@Composable
private fun NewConnection(busy: Boolean, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    WalletPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.feature_settings_new_connection)) },
                placeholder = { Text("Claude Desktop") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onCreate(name)
                    name = ""
                }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onCreate(name)
                    name = ""
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = WalletTheme.colors.action, contentColor = WalletTheme.colors.onAction),
            ) { Text(stringResource(R.string.feature_settings_make_token)) }
        }
    }
}

/** Claude Code's command and Claude Desktop's config, with the new token in them once there is one. */
@Composable
private fun HowToConnect(state: ClaudeUiState, onCopy: (String, Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val token = state.fresh?.token ?: "<your token>"
    val code = "claude mcp add --transport http wallet ${state.address} --header \"Authorization: Bearer $token\""
    val desktop = """
        {
          "mcpServers": {
            "wallet": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "${state.address}", "--header", "Authorization:${'$'}{WALLET_TOKEN}"],
              "env": { "WALLET_TOKEN": "Bearer $token" }
            }
          }
        }
    """.trimIndent()
    WalletPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = open, role = Role.Button, onValueChange = { open = it }).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.feature_settings_how_token), Modifier.weight(1f))
            LineIcon(WalletIcons.expand, contentDescription = null, size = 18.dp, modifier = Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.feature_settings_claude_code_run), style = MaterialTheme.typography.bodySmall)
                CopyRow(code, sensitive = state.fresh != null, onCopy)
                Text(stringResource(R.string.feature_settings_claude_desktop_how), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().background(WalletTheme.colors.sunken, MaterialTheme.shapes.small).padding(12.dp)) {
                    Text(desktop, Modifier.weight(1f).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onCopy(desktop, state.fresh != null) }) { Text(stringResource(R.string.feature_settings_copy)) }
                Muted(stringResource(R.string.feature_settings_claude_then), Modifier.padding(0.dp))
            }
        }
    }
}

@Composable
private fun TokenRow(token: McpToken, onDisconnect: () -> Unit) {
    val first = stringResource(if (token.signedIn) R.string.feature_settings_connected_day else R.string.feature_settings_made_day, shortDateTime(token.createdAt, "yMMMd"))
    val used = token.lastUsedAt?.let { stringResource(R.string.feature_settings_last_used_day, shortDateTime(it, "yMMMd")) }
        ?: stringResource(R.string.feature_settings_not_used)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(token.name)
            Text("$first · $used", style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted)
        }
        TextButton(onClick = onDisconnect) { Text(stringResource(R.string.feature_settings_disconnect), color = WalletTheme.colors.danger) }
    }
}

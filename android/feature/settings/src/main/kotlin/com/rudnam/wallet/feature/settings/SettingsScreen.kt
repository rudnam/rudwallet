package com.rudnam.wallet.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rudnam.wallet.core.designsystem.component.WalletPanel
import com.rudnam.wallet.core.designsystem.component.rememberHaptics
import com.rudnam.wallet.core.designsystem.icon.LineIcon
import com.rudnam.wallet.core.designsystem.icon.WalletIcons
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.datastore.Appearance
import java.util.Locale

/** Where Settings' rows lead. */
interface SettingsNavigation {
    /** A page by its website path: "settings?section=accounts", "budgets". Native or not, the app knows. */
    fun open(path: String)

    fun back()
}

/** A row of the menu: its section (the website's ?section=…), or another page. */
private data class Section(val id: String, val label: Int, val glyph: String, val group: Int, val path: String = "settings?section=$id")

private val sections = listOf(
    Section("accounts", R.string.feature_settings_accounts, WalletIcons.glyphs.getValue("bank"), R.string.feature_settings_group_money),
    Section("categories", R.string.feature_settings_categories, WalletIcons.glyphs.getValue("other"), R.string.feature_settings_group_money),
    Section("budgets", R.string.feature_settings_budgets, WalletIcons.budgets, R.string.feature_settings_group_money, path = "budgets"),
    Section("rules", R.string.feature_settings_rules, WalletIcons.glyphs.getValue("shopping"), R.string.feature_settings_group_money),
    Section("notifications", R.string.feature_settings_notifications, WalletIcons.glyphs.getValue("card"), R.string.feature_settings_group_recording),
    Section("import", R.string.feature_settings_import, WalletIcons.glyphs.getValue("school"), R.string.feature_settings_group_recording),
    Section("appearance", R.string.feature_settings_appearance, WalletIcons.appearance, R.string.feature_settings_group_app),
    Section("language", R.string.feature_settings_language, WalletIcons.globe, R.string.feature_settings_group_app),
    Section("widgets", R.string.feature_settings_widgets, WalletIcons.glyphs.getValue("phone"), R.string.feature_settings_group_app),
    Section("claude", R.string.feature_settings_claude, WalletIcons.spark, R.string.feature_settings_group_app),
)

private val groups = listOf(R.string.feature_settings_group_money, R.string.feature_settings_group_recording, R.string.feature_settings_group_app)

@Composable
internal fun SettingsDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    SettingsMessages(viewModel, snackbar)
    SettingsScreen(state, deleting, viewModel, navigation)
}

/** The snackbar for what Settings' own changes said. */
@Composable
internal fun SettingsMessages(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(messages) {
        val message = messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown(message)
        snackbar.showSnackbar(
            when (message) {
                SettingsMessage.Offline -> resources.getString(R.string.feature_settings_offline)
                is SettingsMessage.Refused -> message.message
            },
        )
    }
}

/** Settings, the fourth tab: a short menu in groups, then who's signed in (docs/design, "settings"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: SettingsUiState, deleting: DeleteAccountState?, actions: SettingsActions, navigation: SettingsNavigation) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = TopAppBarDefaults.windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_title)) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (group in groups) {
                item(key = group) {
                    Text(
                        stringResource(group),
                        style = MaterialTheme.typography.labelLarge,
                        color = WalletTheme.colors.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                item(key = "$group-rows") {
                    WalletPanel(Modifier.fillMaxWidth()) {
                        val rows = sections.filter { it.group == group }
                        rows.forEachIndexed { i, s ->
                            MenuRow(s, summary(s.id, state), onClick = { navigation.open(s.path) })
                            if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp), color = WalletTheme.colors.border)
                        }
                    }
                }
            }
            item(key = "me") { Me(state, actions) }
        }
    }
    if (deleting != null && state.me != null) DeleteAccountDialog(state.me.email, state.me.hasPassword, deleting, actions)
}

@Composable
private fun summary(id: String, state: SettingsUiState): String {
    val w = state.wallet ?: return ""
    return when (id) {
        "accounts" -> w.activeAccounts.size.let { pluralStringResource(R.plurals.feature_settings_account_count, it, it) }
        "categories" -> w.categories.size.let { pluralStringResource(R.plurals.feature_settings_category_count, it, it) }
        "notifications" -> if (w.settings.autoConfirm) stringResource(R.string.feature_settings_auto_confirm_on) else ""
        "import" -> "PayPay, MUFG, BudgetBakers"
        "appearance" -> stringResource(appearanceName(state.appearance))
        "language" -> currentLanguage()?.let(::languageName) ?: stringResource(R.string.feature_settings_automatic)
        else -> ""
    }
}

@Composable
private fun MenuRow(section: Section, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = WalletTheme.colors.surface),
        leadingContent = {
            Box(Modifier.size(36.dp).background(WalletTheme.colors.sunken, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                LineIcon(section.glyph, contentDescription = null, tint = WalletTheme.colors.text, size = 20.dp)
            }
        },
        headlineContent = { Text(stringResource(section.label)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LineIcon(WalletIcons.chevron, contentDescription = null, tint = WalletTheme.colors.muted, size = 18.dp)
            }
        },
    )
}

/** Who's signed in, with Sign out and Delete account; a demo has End demo. */
@Composable
private fun Me(state: SettingsUiState, actions: SettingsActions) {
    val me = state.me ?: return
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WalletPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!me.demo) {
                    Box(Modifier.size(40.dp).background(WalletTheme.colors.sunken, CircleShape), contentAlignment = Alignment.Center) {
                        Text(me.email.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(
                        stringResource(if (me.demo) R.string.feature_settings_demo else R.string.feature_settings_signed_in_as),
                        style = MaterialTheme.typography.bodySmall,
                        color = WalletTheme.colors.muted,
                    )
                    Text(if (me.demo) stringResource(R.string.feature_settings_demo_detail) else me.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = actions::signOut) {
                    Text(stringResource(if (me.demo) R.string.feature_settings_end_demo else R.string.feature_settings_sign_out))
                }
            }
        }
        if (!me.demo) {
            TextButton(onClick = actions::startDeleting, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.feature_settings_delete_account), color = WalletTheme.colors.danger)
            }
        }
    }
}

/** Says plainly what goes, and asks for the password (or, for a Google-only account, the email typed out). */
@Composable
private fun DeleteAccountDialog(email: String, hasPassword: Boolean, state: DeleteAccountState, actions: SettingsActions) {
    AlertDialog(
        onDismissRequest = actions::cancelDeleting,
        title = { Text(stringResource(R.string.feature_settings_delete_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.feature_settings_delete_account_detail, email), color = WalletTheme.colors.muted)
                OutlinedTextField(
                    value = state.text,
                    onValueChange = actions::typeConfirmation,
                    label = { Text(stringResource(if (hasPassword) R.string.feature_settings_your_password else R.string.feature_settings_type_email)) },
                    placeholder = if (hasPassword) null else ({ Text(email) }),
                    singleLine = true,
                    isError = state.refused != null,
                    supportingText = state.refused?.let { { Text(it) } },
                    visualTransformation = if (hasPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (hasPassword) KeyboardType.Password else KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.deleteAccount() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions::deleteAccount, enabled = !state.busy && state.text.isNotBlank()) {
                Text(stringResource(R.string.feature_settings_delete_everything), color = WalletTheme.colors.danger)
            }
        },
        dismissButton = { TextButton(onClick = actions::cancelDeleting) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

/** A settings page: its title, a way back, and its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    navigation: SettingsNavigation,
    floatingActionButton: @Composable () -> Unit = {},
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
                    IconButton(onClick = navigation::back) { LineIcon(WalletIcons.back, contentDescription = stringResource(R.string.feature_settings_back)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = WalletTheme.colors.surface),
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A list of choices, one chosen: radio buttons in a panel, each row the whole tap target. */
@Composable
internal fun <T> Choices(options: List<Pair<T, String>>, chosen: T, onChoose: (T) -> Unit, detail: (T) -> String? = { null }) {
    val haptics = rememberHaptics()
    WalletPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { i, (value, label) ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = value == chosen, role = Role.RadioButton, onClick = {
                            if (value != chosen) haptics.select()
                            onChoose(value)
                        })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == chosen, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        detail(value)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted) }
                    }
                }
                if (i < options.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = WalletTheme.colors.border)
            }
        }
    }
}

internal fun appearanceName(a: Appearance) = when (a) {
    Appearance.System -> R.string.feature_settings_automatic
    Appearance.Light -> R.string.feature_settings_light
    Appearance.Dark -> R.string.feature_settings_dark
}

@Composable
internal fun AppearanceDestination(navigation: SettingsNavigation, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppearanceScreen(state.appearance, viewModel::setAppearance, navigation)
}

@Composable
internal fun AppearanceScreen(appearance: Appearance, onChoose: (Appearance) -> Unit, navigation: SettingsNavigation) {
    val automatic = stringResource(R.string.feature_settings_automatic_detail)
    val options = Appearance.entries.map { it to stringResource(appearanceName(it)) }
    SettingsPage(stringResource(R.string.feature_settings_appearance), navigation) {
        item { Choices(options, appearance, onChoose, detail = { if (it == Appearance.System) automatic else null }) }
    }
}

/** The app's own language, or null to follow the phone's: Android's per-app language. */
internal fun currentLanguage(): String? = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifEmpty { null }

/** A language's name in that language, so it can be found from either. */
private fun languageName(code: String) = if (code == "ja") "日本語" else "English"

@Composable
internal fun LanguageDestination(navigation: SettingsNavigation) {
    // The phone's own language, which Automatic follows.
    val phone = LocaleListCompat.getAdjustedDefault()[0]?.language ?: Locale.getDefault().language
    LanguageScreen(
        chosen = currentLanguage(),
        phone = if (phone == "ja") "ja" else "en",
        // Android remakes the screen in the new language; the web view follows (RudWalletApp).
        onChoose = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it.orEmpty())) },
        navigation = navigation,
    )
}

@Composable
internal fun LanguageScreen(chosen: String?, phone: String, onChoose: (String?) -> Unit, navigation: SettingsNavigation) {
    val options = listOf<Pair<String?, String>>(
        null to stringResource(R.string.feature_settings_automatic_language, languageName(phone)),
        "en" to languageName("en"),
        "ja" to languageName("ja"),
    )
    SettingsPage(stringResource(R.string.feature_settings_language), navigation) {
        item { Choices(options, chosen, onChoose) }
        item { Text(stringResource(R.string.feature_settings_language_detail), style = MaterialTheme.typography.bodySmall, color = WalletTheme.colors.muted, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

/** The currencies offered, as on the website. */
private val currencies = listOf("JPY", "USD", "EUR", "GBP", "PHP", "KRW", "CNY", "TWD", "AUD", "CAD", "SGD", "HKD", "THB", "INR")

@Composable
internal fun CurrencyDestination(navigation: SettingsNavigation, snackbar: SnackbarHostState, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsMessages(viewModel, snackbar)
    CurrencyScreen(state.wallet?.settings?.currency, viewModel::setCurrency, navigation)
}

/** The main currency: only until the first record (Wallet's checklist links here; it's not in the menu). */
@Composable
internal fun CurrencyScreen(currency: String?, onChoose: (String) -> Unit, navigation: SettingsNavigation) {
    val list = if (currency == null || currency in currencies) currencies else listOf(currency) + currencies
    val options = list.map { code -> code to (runCatching { java.util.Currency.getInstance(code).getDisplayName(Locale.getDefault()) }.getOrNull()?.let { "$code · $it" } ?: code) }
    SettingsPage(stringResource(R.string.feature_settings_currency), navigation) {
        item { Text(stringResource(R.string.feature_settings_currency_detail), style = MaterialTheme.typography.bodyMedium, color = WalletTheme.colors.muted, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (currency != null) item { Choices(options, currency, onChoose) }
    }
}

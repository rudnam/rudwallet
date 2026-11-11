package com.rudnam.wallet.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.CardRule
import com.rudnam.wallet.core.model.McpToken
import com.rudnam.wallet.core.model.Me
import com.rudnam.wallet.core.model.NotificationStatus
import com.rudnam.wallet.core.model.PayeeRule
import com.rudnam.wallet.core.model.PaymentNotification
import com.rudnam.wallet.core.model.Rules
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings' pages at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigation = object : SettingsNavigation {
        override fun open(path: String) {}
        override fun back() {}
    }
    private val settings = object : SettingsActions {
        override fun setAppearance(appearance: Appearance) {}
        override fun setCurrency(currency: String) {}
        override fun signOut() {}
        override fun startDeleting() {}
        override fun typeConfirmation(text: String) {}
        override fun deleteAccount() {}
        override fun cancelDeleting() {}
    }
    private val accounts = object : AccountsActions {
        override fun create() {}
        override fun edit(account: Account) {}
        override fun setKind(kind: AccountKind) {}
        override fun setName(name: String) {}
        override fun setBalance(text: String) {}
        override fun setColor(color: String) {}
        override fun setArchived(archived: Boolean) {}
        override fun save(defaultName: String, defaultColor: String) {}
        override fun askDelete(asking: Boolean) {}
        override fun delete() {}
        override fun close() {}
    }
    private val categories = object : CategoriesActions {
        override fun create() {}
        override fun createUnder(parent: Category) {}
        override fun edit(category: Category) {}
        override fun addMissing(category: Category) {}
        override fun regroup() {}
        override fun setName(name: String) {}
        override fun setKind(kind: CategoryKind) {}
        override fun setParent(parentId: Long?) {}
        override fun setIcon(icon: String) {}
        override fun setColor(color: String) {}
        override fun save() {}
        override fun askDelete(asking: Boolean) {}
        override fun delete() {}
        override fun close() {}
    }

    private val state = SettingsUiState(SampleWallet.wallet, Me("francis@example.com", hasPassword = true, demo = false), Appearance.System)

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { WalletTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-$name.png")
    }

    @Test
    fun menu() = shot("menu") { SettingsScreen(state, null, settings, navigation) }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun menuWhole() = shot("menu-whole") { SettingsScreen(state, null, settings, navigation) }

    @Test
    fun menuDark() = shot("menu-dark", dark = true) { SettingsScreen(state, null, settings, navigation) }

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun menuJapanese() = shot("menu-ja") { SettingsScreen(state, null, settings, navigation) }

    @Test
    fun accountList() = shot("accounts") { AccountsScreen(SampleWallet.wallet, null, accounts, navigation) }

    @Test
    fun newAccount() = shot("account-new") {
        AccountsScreen(SampleWallet.wallet, AccountEditor(null, AccountKind.EMoney, "", "", "", false), accounts, navigation)
    }

    @Test
    fun categoryList() = shot("categories") {
        CategoriesScreen(SampleWallet.wallet, listOf(Category(0, "Pets", CategoryKind.Expense, "pet", "#8a6d3b")), null, categories, navigation)
    }

    /** An old wallet: Groceries beside Food & Drinks, offered to go under it. */
    @Test
    fun regroupOffer() = shot("categories-regroup") {
        CategoriesScreen(SampleWallet.wallet, emptyList(), null, categories, navigation, regroup = listOf(SampleWallet.wallet.category(11)!!.copy(parentId = 10)))
    }

    /** Food & Drinks with Cafés and Lunch under it; the rest main categories. */
    private val nested = SampleWallet.wallet.copy(
        categories = SampleWallet.wallet.categories +
            Category(20, "Cafés", CategoryKind.Expense, "coffee", "#8a5a3c", parentId = 10) +
            Category(21, "Lunch", CategoryKind.Expense, "food", "#d9534f", parentId = 10),
    )

    @Test
    fun subcategoryList() = shot("categories-nested") {
        CategoriesScreen(nested, emptyList(), null, categories, navigation)
    }

    @Test
    fun editSubcategory() = shot("subcategory-edit") {
        CategoriesScreen(nested, emptyList(), CategoryEditor(20, "Cafés", CategoryKind.Expense, "coffee", "#8a5a3c", parentId = 10), categories, navigation)
    }

    @Test
    fun editCategory() = shot("category-edit") {
        CategoriesScreen(SampleWallet.wallet, emptyList(), CategoryEditor(10, "Food & Drinks", CategoryKind.Expense, "food", "#d9534f"), categories, navigation)
    }

    @Test
    fun appearance() = shot("appearance") { AppearanceScreen(Appearance.System, {}, navigation) }

    @Test
    fun language() = shot("language") { LanguageScreen(null, "en", {}, navigation) }

    private val rulesActions = object : RulesActions {
        override fun setShop(key: String, name: String, categoryId: Long) {}
        override fun forgetShop(key: String, name: String) {}
        override fun addShop(name: String, categoryId: Long) {}
        override fun setCard(source: String, accountId: Long) {}
        override fun forgetCard(source: String) {}
        override fun reload() {}
    }
    private val notificationsActions = object : NotificationsActions {
        override fun setAutoConfirm(on: Boolean) {}
        override fun setDefaultAccount(accountId: Long?) {}
    }
    private val claudeActions = object : ClaudeActions {
        override fun create(name: String) {}
        override fun askDisconnect(token: McpToken?) {}
        override fun disconnect() {}
    }
    private val rules = Rules(
        payees = listOf(PayeeRule("doutor", "Doutor", 13, 12), PayeeRule("ramen taro", "Ramen Taro", 10, 3), PayeeRule("life", "Life Supermarket", 11, 1)),
        cards = listOf(CardRule("PayPay Card", 5, 21), CardRule("Google Wallet Visa", 2, 4)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun shopsAndCards() = shot("rules") { RulesScreen(SampleWallet.wallet, Loadable.Ready(rules), rulesActions, navigation) }

    @Test
    @Config(qualifiers = "ja-w411dp-h1300dp-xxhdpi")
    fun shopsAndCardsJapanese() = shot("rules-ja") { RulesScreen(SampleWallet.wallet, Loadable.Ready(rules), rulesActions, navigation) }

    private val notifications = listOf(
        PaymentNotification(3, "PayPay", "PayPay", "ドトール で ¥480 支払いました", SampleWallet.at(14, 8, 10), NotificationStatus.Payment, ""),
        PaymentNotification(2, "Google Wallet", "FamilyMart", "", SampleWallet.at(14, 8, 55), NotificationStatus.Unparsed, "No amount in the notification."),
        PaymentNotification(1, "PayPay", "PayPay", "チャージが完了しました", SampleWallet.at(12, 19, 2), NotificationStatus.Ignored, "Not a payment made (declined, a balance or a top-up)."),
    )

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun paymentNotifications() = shot("notifications") {
        NotificationsScreen(SampleWallet.wallet, access = false, autoConfirm = null, recent = Loadable.Ready(notifications), actions = notificationsActions, onOpenAccess = {}, navigation = navigation)
    }

    @Test
    @Config(qualifiers = "ja-w411dp-h1300dp-xxhdpi")
    fun paymentNotificationsJapanese() = shot("notifications-ja") {
        NotificationsScreen(SampleWallet.wallet, access = true, autoConfirm = null, recent = Loadable.Ready(notifications), actions = notificationsActions, onOpenAccess = {}, navigation = navigation)
    }

    @Test
    fun widgets() = shot("widgets") { WidgetsScreen(canPin = true, onPin = {}, navigation = navigation) }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun claude() = shot("claude") {
        val fresh = McpToken(2, "Claude Desktop", SampleWallet.at(14, 9, 0), null, "wallet_3f9a0c1e7b2d4a6f8e1c3b5d7f9a1c3e", false)
        val tokens = listOf(McpToken(1, "Claude", SampleWallet.at(2, 10, 0), SampleWallet.at(13, 21, 0), null, true), fresh.copy(token = null))
        ClaudeScreen(state.me, ClaudeUiState(state.me, "https://wallet.example.com/mcp", Loadable.Ready(tokens), fresh = fresh), claudeActions, onCopy = { _, _ -> }, navigation = navigation)
    }
}

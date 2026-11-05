package com.rudnam.wallet.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.datastore.Appearance
import com.rudnam.wallet.core.model.Account
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.Me
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
        override fun edit(category: Category) {}
        override fun addMissing(category: Category) {}
        override fun setName(name: String) {}
        override fun setKind(kind: CategoryKind) {}
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

    @Test
    fun editCategory() = shot("category-edit") {
        CategoriesScreen(SampleWallet.wallet, emptyList(), CategoryEditor(10, "Food & Drinks", CategoryKind.Expense, "food", "#d9534f"), categories, navigation)
    }

    @Test
    fun appearance() = shot("appearance") { AppearanceScreen(Appearance.System, {}, navigation) }

    @Test
    fun language() = shot("language") { LanguageScreen(null, "en", {}, navigation) }
}

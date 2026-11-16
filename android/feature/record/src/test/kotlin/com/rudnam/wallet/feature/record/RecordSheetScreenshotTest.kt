package com.rudnam.wallet.feature.record

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.testing.SampleWallet
import com.rudnam.wallet.core.ui.CategoryPickerContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone

/** The record sheet's insides at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class RecordSheetScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val nothing = object : RecordActions {
        override fun kind(kind: TransactionKind) {}
        override fun press(key: PadKey) {}
        override fun category(id: Long) {}
        override fun pickCategory(id: Long) {}
        override fun allCategories(show: Boolean) {}
        override fun payee(payee: String) {}
        override fun account(id: Long) {}
        override fun toAccount(id: Long) {}
        override fun time(at: Instant) {}
        override fun note(note: String) {}
        override fun addTag(text: String) {}
        override fun removeTag(tag: String) {}
        override fun extras() {}
        override fun save() {}
        override fun confirmChanges() {}
        override fun cancelChanges() {}
        override fun askDelete() {}
        override fun cancelDelete() {}
        override fun delete() {}
    }

    private val expenses = SampleWallet.wallet.categories.filter { it.kind.apiName == "expense" }

    private fun state(form: RecordForm, record: com.rudnam.wallet.core.model.Transaction? = null, extras: Boolean = false) = RecordUiState.Ready(
        wallet = SampleWallet.wallet,
        form = form,
        record = record,
        categories = if (form.kind == TransactionKind.Income) SampleWallet.wallet.categories.filter { it.kind.apiName == "income" } else expenses,
        hasMoreCategories = false,
        recentPayees = if (record == null && form.payee.isEmpty()) listOf("Doutor", "Life Supermarket", "Ramen Taro") else emptyList(),
        payeeSuggestions = emptyList(),
        tagSuggestions = listOf("dinner", "trip"),
        showExtras = extras,
        busy = false,
        problem = null,
        serverMessage = null,
        confirming = null,
        confirmingDelete = false,
    )

    private fun shot(name: String, state: RecordUiState.Ready, dark: Boolean = false) {
        compose.setContent {
            WalletTheme(darkTheme = dark) {
                Surface(color = WalletTheme.colors.surface) { RecordSheetContent(state, nothing) }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record-$name.png")
    }

    private val newRecord = RecordForm(occurredAt = SampleWallet.now, amount = "1,280", categoryId = 10, accountId = 1, toAccountId = 3)

    @Test
    fun new() = shot("new", state(newRecord))

    @Test
    fun dark() = shot("new-dark", state(newRecord), dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("new-ja", state(newRecord))

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun review() {
        val record = SampleWallet.records.first { it.id == 20L }.copy(sourceLabel = "PayPay")
        shot("review", state(RecordForm.from(record, 0), record, extras = true))
    }

    /** A new wallet's expense categories: main ones, each followed by its subcategories. */
    private val tree: Wallet = run {
        val mains = listOf(
            "Food & Drinks" to listOf("Groceries", "Restaurants", "Cafés", "Delivery"),
            "Shopping" to listOf("Clothing", "Household", "Electronics"),
            "Housing" to listOf("Rent", "Bills & Utilities", "Phone & Internet"),
            "Transport" to listOf("Public transport", "Taxi", "Car"),
            "Health" to listOf("Medical", "Pharmacy"),
            "Education" to emptyList(),
            "Other" to emptyList(),
        )
        val icons = mapOf(
            "Food & Drinks" to "food", "Groceries" to "grocery", "Restaurants" to "food", "Cafés" to "coffee", "Delivery" to "other",
            "Shopping" to "shopping", "Clothing" to "clothes", "Household" to "clean", "Electronics" to "laptop",
            "Housing" to "home", "Rent" to "home", "Bills & Utilities" to "bills", "Phone & Internet" to "phone",
            "Transport" to "transit", "Public transport" to "transit", "Taxi" to "car", "Car" to "car",
            "Health" to "health", "Medical" to "health", "Pharmacy" to "shield", "Education" to "school", "Other" to "other",
        )
        var id = 100L
        val cats = mains.flatMapIndexed { i, (main, subs) ->
            val colour = listOf("#d9534f", "#c9a227", "#8a5cc2", "#3b82c4", "#2aa198", "#5b4a8a", "#7a7f7c")[i]
            val parent = id++
            listOf(Category(parent, main, CategoryKind.Expense, icons[main].orEmpty(), colour)) +
                subs.map { Category(id++, it, CategoryKind.Expense, icons[it].orEmpty(), colour, parentId = parent) }
        }
        SampleWallet.wallet.copy(categories = cats)
    }

    /** The boxes: the most used, subcategories among them (with their main category under the name), and All. */
    @Test
    fun suggestions() {
        val shown = listOf("Cafés", "Groceries", "Restaurants", "Public transport", "Rent", "Food & Drinks", "Taxi").map { n -> tree.categories.first { it.name == n } }
        shot(
            "suggestions",
            state(newRecord.copy(categoryId = shown.first().id)).copy(wallet = tree, categories = shown, hasMoreCategories = true),
        )
    }

    /** All: the picker with every category, the chosen one ticked. */
    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun picker() {
        compose.setContent {
            WalletTheme {
                Surface(color = WalletTheme.colors.surface) {
                    CategoryPickerContent(tree, CategoryKind.Expense, selectedId = tree.categories.first { it.name == "Cafés" }.id, onPick = {})
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record-picker.png")
    }

    @Test
    fun transfer() = shot("transfer", state(newRecord.copy(kind = TransactionKind.Transfer, amount = "10,000", accountId = 2, toAccountId = 3)))
}

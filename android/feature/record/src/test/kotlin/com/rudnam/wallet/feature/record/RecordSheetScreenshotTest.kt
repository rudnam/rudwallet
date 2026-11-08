package com.rudnam.wallet.feature.record

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryKind
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.testing.SampleWallet
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
        override fun allCategories(show: Boolean) {}
        override fun searchCategories(query: String) {}
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
        canFoldCategories = false,
        showAllCategories = false,
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

    /** A new wallet's expense categories, all of them open, with the search above. */
    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun allCategories() {
        val defaults = listOf(
            "Food & Drinks" to "food", "Groceries" to "grocery", "Shopping" to "shopping", "Clothing" to "clothes",
            "Household" to "clean", "Transport" to "transit", "Car" to "car", "Housing" to "home",
            "Bills & Utilities" to "bills", "Phone & Internet" to "phone", "Subscriptions" to "repeat", "Insurance" to "shield",
            "Health" to "health", "Personal care" to "beauty", "Sport & Fitness" to "sport", "Entertainment" to "fun",
            "Education" to "school", "Travel" to "travel", "Kids & Family" to "kids", "Pets" to "pet",
            "Gifts & Donations" to "gift", "Taxes & Fees" to "bank", "Other" to "other",
        ).mapIndexed { i, (name, icon) -> Category(100L + i, name, CategoryKind.Expense, icon, "#5f7387") }
        shot(
            "all-categories",
            state(newRecord.copy(categoryId = 100)).copy(
                categories = defaults,
                canFoldCategories = true,
                showAllCategories = true,
                canSearchCategories = true,
            ),
        )
    }

    @Test
    fun transfer() = shot("transfer", state(newRecord.copy(kind = TransactionKind.Transfer, amount = "10,000", accountId = 2, toAccountId = 3)))
}

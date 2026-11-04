package com.rudnam.wallet.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshots of the native Wallet, drawn on the JVM by Robolectric: a phone
 * (411 × 891 dp), light and dark, in English and Japanese. They're saved in
 * app/build/outputs/roborazzi; CI keeps them as the native-screenshots
 * artifact, so a change can be looked at without a phone.
 *
 *   ./gradlew testDebugUnitTest -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WalletScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val noActions = object : WalletActions {
        override fun open(page: String, params: Map<String, String>) {}
        override fun openRecord(t: Transaction) {}
        override fun add() {}
        override fun refresh() {}
    }

    @Before
    fun tokyo() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    private fun shot(name: String, state: WalletState, locale: Locale = Locale.US, dark: Boolean = false) {
        compose.setContent {
            Localized(locale) {
                WalletTheme(dark = dark) {
                    val format = (state as? WalletState.Ready)?.let { Format(locale, it.data.overview.settings) }
                    WalletScreen(state, format, noActions, now = Sample.now)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun light() = shot("wallet-light", WalletState.Ready(Sample.home()))

    /** The whole page, every card and record included, on a very tall phone. */
    @Test
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    fun whole() = shot("wallet-whole", WalletState.Ready(Sample.home()))

    @Test
    fun dark() = shot("wallet-dark", WalletState.Ready(Sample.home()), dark = true)

    @Test
    fun japanese() = shot("wallet-ja", WalletState.Ready(Sample.home()), locale = Locale.JAPAN)

    @Test
    fun offline() = shot("wallet-offline", WalletState.Ready(Sample.home(), offline = true))

    @Test
    fun newWallet() = shot("wallet-new", WalletState.Ready(Sample.home(empty = true)))

    @Test
    fun failed() = shot("wallet-failed", WalletState.Failed)
}

/** A wallet like the demo's, as the server would send it. */
object Sample {
    val now: Date = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 14, 9, 30)
    }.time

    private fun at(day: Int, h: Int, m: Int) = Parse.rfc3339(
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, h, m)
        }.time,
    )

    fun home(empty: Boolean = false): HomeData {
        val wallet = JSONObject(
            """
            {"settings": {"currency": "JPY", "currency_exponent": 0, "auto_confirm": false, "default_account_id": 1},
             "accounts": [
               {"id": 1, "name": "Cash", "kind": "cash", "initial_balance": 0, "balance": 26450, "color": "#4f8a4c", "archived": false},
               {"id": 3, "name": "PayPay", "kind": "e_money", "initial_balance": 0, "balance": 30140, "color": "#d4553f", "archived": false},
               {"id": 5, "name": "PayPay Card", "kind": "credit_card", "initial_balance": 0, "balance": -46770, "color": "#c8463d", "archived": false},
               {"id": 2, "name": "MUFG", "kind": "bank", "initial_balance": 0, "balance": 1001780, "color": "#3b6ea8", "archived": false},
               {"id": 4, "name": "Old card", "kind": "credit_card", "initial_balance": 0, "balance": 0, "color": "#888888", "archived": true}
             ],
             "categories": [
               {"id": 10, "name": "Food & Drinks", "kind": "expense", "icon": "food", "color": "#c96442"},
               {"id": 11, "name": "Groceries", "kind": "expense", "icon": "grocery", "color": "#2e7d4f"},
               {"id": 12, "name": "Transport", "kind": "expense", "icon": "transit", "color": "#3b6ea5"},
               {"id": 13, "name": "Coffee", "kind": "expense", "icon": "☕", "color": "#8a5a3c"},
               {"id": 14, "name": "Salary", "kind": "income", "icon": "salary", "color": "#2e7d4f"}
             ],
             "review_count": ${if (empty) 0 else 2}}
            """,
        )
        fun r(id: Int, kind: String, amount: Int, account: Int, category: Int?, day: Int, h: Int, m: Int, payee: String, status: String = "confirmed", source: String = "manual", tags: String = "[]", to: Int? = null) =
            """{"id": $id, "kind": "$kind", "amount": $amount, "account_id": $account, "to_account_id": $to, "category_id": $category,
                "occurred_at": "${at(day, h, m)}", "payee": "$payee", "note": "", "status": "$status", "source": "$source",
                "source_label": "", "duplicate_of_id": null, "created_at": "${at(day, h, m)}", "tags": $tags}"""
        val records = if (empty) "[]" else listOf(
            r(20, "expense", 0, 3, null, 14, 8, 55, "FamilyMart", status = "review", source = "notification"),
            r(19, "expense", 480, 3, 13, 14, 8, 10, "Doutor", source = "notification"),
            r(18, "expense", 1280, 1, 10, 13, 19, 40, "Ramen Taro", tags = "[\"dinner\"]"),
            r(17, "transfer", 10000, 2, null, 13, 12, 0, "", to = 3),
            r(16, "expense", 3420, 2, 11, 12, 18, 5, "Life Supermarket", source = "email"),
            r(15, "expense", 2100, 3, 12, 10, 9, 0, "Suica top-up"),
            r(14, "income", 280000, 2, 14, 10, 9, 0, "Acme KK"),
            r(13, "expense", 5600, 2, 10, 7, 20, 30, "Izakaya Hana"),
            r(12, "expense", 980, 1, 13, 5, 8, 15, "Starbucks"),
            r(11, "expense", 4210, 2, 11, 3, 18, 45, "Life Supermarket"),
            r(10, "expense", 1500, 1, null, 2, 13, 0, "Flea market"),
        ).joinToString(",", "[", "]")
        val budgets = if (empty) "[]" else """[{"id": 1, "name": "Everything", "category_id": null, "amount": 60000},
            {"id": 2, "name": "Eating out", "category_id": 10, "amount": 8000}]"""
        return HomeData(Parse.overview(wallet), Parse.transactions(JSONArray(records)), Parse.budgets(JSONArray(budgets)))
    }
}

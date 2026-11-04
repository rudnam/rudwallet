package com.rudnam.wallet.screens

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The server's answers, as the native screens use them. They mirror the Go
// handlers' JSON, like web/src/api.ts. Amounts are whole minor units of the
// user's currency. Fields the native screens don't use yet are left out:
// parsing ignores them, so the server can add more at any time.

data class Settings(val currency: String, val exponent: Int)

data class Account(
    val id: Long,
    val name: String,
    /** cash, bank, credit_card, e_money, savings or other. */
    val kind: String,
    val balance: Long,
    val color: String,
    val archived: Boolean,
)

data class Category(val id: Long, val name: String, val icon: String, val color: String)

data class Overview(
    val settings: Settings,
    val accounts: List<Account>,
    val categories: List<Category>,
    val reviewCount: Int,
) {
    private val accountsById = accounts.associateBy { it.id }
    private val categoriesById = categories.associateBy { it.id }
    fun account(id: Long?): Account? = id?.let { accountsById[it] }
    fun category(id: Long?): Category? = id?.let { categoriesById[it] }
}

enum class Kind { EXPENSE, INCOME, TRANSFER }

data class Transaction(
    val id: Long,
    val kind: Kind,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long?,
    val categoryId: Long?,
    val occurredAt: Date,
    val payee: String,
    val confirmed: Boolean,
    /** manual, email, import or notification. */
    val source: String,
    val tags: List<String>,
    /** The record as the server sent it, handed to the web app to open it. */
    val json: JSONObject,
)

data class Budget(val id: Long, val name: String, val categoryId: Long?, val amount: Long)

/** Everything Wallet shows. */
data class HomeData(
    val overview: Overview,
    /** This month's records, newest first. */
    val records: List<Transaction>,
    val budgets: List<Budget>,
)

internal object Parse {
    fun overview(o: JSONObject): Overview {
        val s = o.getJSONObject("settings")
        return Overview(
            settings = Settings(s.getString("currency"), s.optInt("currency_exponent", 0)),
            accounts = o.getJSONArray("accounts").objects().map {
                Account(
                    id = it.getLong("id"),
                    name = it.getString("name"),
                    kind = it.optString("kind", "other"),
                    balance = it.getLong("balance"),
                    color = it.optString("color"),
                    archived = it.optBoolean("archived"),
                )
            },
            categories = o.getJSONArray("categories").objects().map {
                Category(it.getLong("id"), it.getString("name"), it.optString("icon"), it.optString("color"))
            },
            reviewCount = o.optInt("review_count"),
        )
    }

    fun transactions(a: JSONArray): List<Transaction> = a.objects().map {
        Transaction(
            id = it.getLong("id"),
            kind = when (it.getString("kind")) {
                "income" -> Kind.INCOME
                "transfer" -> Kind.TRANSFER
                else -> Kind.EXPENSE
            },
            amount = it.getLong("amount"),
            accountId = it.getLong("account_id"),
            toAccountId = it.optLongOrNull("to_account_id"),
            categoryId = it.optLongOrNull("category_id"),
            occurredAt = time(it.getString("occurred_at")),
            payee = it.optString("payee"),
            confirmed = it.optString("status") == "confirmed",
            source = it.optString("source"),
            tags = it.optJSONArray("tags")?.let { t -> List(t.length()) { i -> t.getString(i) } } ?: emptyList(),
            json = it,
        )
    }

    fun budgets(a: JSONArray): List<Budget> = a.objects().map {
        Budget(it.getLong("id"), it.getString("name"), it.optLongOrNull("category_id"), it.getLong("amount"))
    }

    /**
     * An RFC 3339 time as Go writes it: 2026-11-04T09:30:00Z, with or without
     * fractions of a second, or with an offset like +09:00.
     */
    fun time(s: String): Date {
        val noFraction = s.replace(Regex("""\.\d+"""), "")
        return try {
            // "XXX" reads Z and +09:00 alike (API 24, this app's minimum).
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(noFraction) ?: Date(0)
        } catch (e: ParseException) {
            Date(0)
        }
    }

    /** A time for a query, e.g. from=2026-11-01T00:00:00+09:00. */
    fun rfc3339(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
}

package com.rudnam.wallet.screens

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date

/**
 * The native screens' side of the server API. They sign in with the web
 * app's session: web/src/native.ts keeps its token and the server's address
 * in Capacitor's preferences, which are these SharedPreferences (the widgets
 * read them the same way, widget/Api.java).
 */
class WalletApi(private val context: Context) {
    /** The app isn't signed in, or the server ended its session. */
    class SignedOut : IOException("Not signed in")

    private val app: SharedPreferences get() = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
    private val cache: SharedPreferences get() = context.getSharedPreferences("native-screens-cache", Context.MODE_PRIVATE)

    private val token: String? get() = app.getString("session", null)?.takeIf { it.isNotEmpty() }
    private val server: String? get() = app.getString("server", null)?.takeIf { it.isNotEmpty() }

    val signedIn: Boolean get() = token != null && server != null

    /**
     * Wallet's data for the month that starts at monthStart, from the server.
     * The answers are kept, so the next start can show them at once and an
     * offline phone still has them (see cachedHome).
     */
    suspend fun home(monthStart: Date, language: String): HomeData = withContext(Dispatchers.IO) {
        val end = Calendar.getInstance().apply { time = monthStart; add(Calendar.MONTH, 1) }.time
        val records = "/api/transactions?from=" + enc(Parse.rfc3339(monthStart)) + "&to=" + enc(Parse.rfc3339(end))
        coroutineScope {
            val overview = async { get("/api/wallet", language) }
            val list = async { get(records, language) }
            val budgets = async { get("/api/budgets", language) }
            val raw = Raw(overview.await(), list.await(), budgets.await())
            val data = raw.parse()
            keep(monthStart, raw)
            data
        }
    }

    /** What home() last fetched for this month and this session, or null. */
    fun cachedHome(monthStart: Date): HomeData? {
        val c = cache
        if (c.getString("owner", null) != owner() || c.getLong("month", -1) != monthStart.time) return null
        return try {
            Raw(c.getString("wallet", null) ?: return null, c.getString("records", null) ?: return null, c.getString("budgets", null) ?: return null).parse()
        } catch (e: JSONException) {
            null
        }
    }

    /** Forgets what was kept, e.g. once the session has ended. */
    fun forget() {
        cache.edit().clear().apply()
    }

    private class Raw(val wallet: String, val records: String, val budgets: String) {
        fun parse() = HomeData(
            overview = Parse.overview(JSONObject(wallet)),
            records = Parse.transactions(JSONArray(records)),
            budgets = Parse.budgets(JSONArray(budgets)),
        )
    }

    private fun keep(month: Date, raw: Raw) {
        cache.edit()
            .putString("owner", owner())
            .putLong("month", month.time)
            .putString("wallet", raw.wallet)
            .putString("records", raw.records)
            .putString("budgets", raw.budgets)
            .apply()
    }

    /** Which session the cache belongs to, without keeping the token itself a second time. */
    private fun owner(): String? = token?.let { t ->
        MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun get(path: String, language: String): String {
        val t = token ?: throw SignedOut()
        val s = server ?: throw SignedOut()
        val conn = URL(s + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", language)
            conn.setRequestProperty("Authorization", "Bearer $t")
            conn.setRequestProperty("X-Wallet-Client", "android")
            val status = conn.responseCode
            if (status == 401) {
                forget()
                throw SignedOut()
            }
            if (status >= 400) throw IOException("The server answered $status")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

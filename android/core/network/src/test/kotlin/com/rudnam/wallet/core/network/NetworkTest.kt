package com.rudnam.wallet.core.network

import androidx.core.os.LocaleListCompat
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.model.AccountKind
import com.rudnam.wallet.core.model.TransactionKind
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class NetworkTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    @Test
    fun `the wallet and records read as the server writes them, extra fields and all`() {
        val wallet = json.decodeFromString<NetworkWallet>(
            """{"settings":{"currency":"JPY","currency_exponent":0,"auto_confirm":true,"default_account_id":null,"new_field":1},
               "accounts":[{"id":1,"name":"Cash","kind":"cash","initial_balance":0,"balance":26450,"color":"#4f8a4c","archived":false}],
               "categories":[],"review_count":2}""",
        ).asModel()
        assertEquals(AccountKind.Cash, wallet.accounts.single().kind)
        assertEquals(2, wallet.reviewCount)

        val record = json.decodeFromString<NetworkTransaction>(
            """{"id":7,"kind":"expense","amount":480,"account_id":3,"to_account_id":null,"category_id":null,
               "occurred_at":"2026-11-14T08:10:00.123456+09:00","payee":"Doutor","note":"","status":"review",
               "source":"notification","source_label":"","duplicate_of_id":null,"created_at":"2026-11-13T23:10:01Z","tags":["coffee"]}""",
        ).asModel()
        assertEquals(Instant.parse("2026-11-13T23:10:00.123456Z"), record.occurredAt)
        assertEquals(TransactionKind.Expense, record.kind)
        assertEquals(TransactionStatus.Review, record.status)
        assertNull(record.categoryId)
        assertEquals(listOf("coffee"), record.tags)
    }

    private class FakeSession(token: String?) : SessionStore {
        override val token = MutableStateFlow(token)
        override fun currentToken() = token.value
        override fun save(token: String) { this.token.value = token }
        override fun clear() { token.value = null }
        override fun keepGoogleVerifier(verifier: String) = Unit
        override fun takeGoogleVerifier(): String? = null
    }

    private fun client(session: SessionStore) = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(session) { LocaleListCompat.forLanguageTags("ja") })
        .build()

    @Test
    fun `requests are signed in as the Android app`() {
        server.enqueue(MockResponse().setBody("{}"))
        client(FakeSession("t0k3n")).newCall(Request.Builder().url(server.url("/api/wallet")).build()).execute().close()
        val sent = server.takeRequest()
        assertEquals("Bearer t0k3n", sent.getHeader("Authorization"))
        assertEquals("android", sent.getHeader("X-Wallet-Client"))
        assertEquals("ja", sent.getHeader("Accept-Language"))
    }

    @Test
    fun `a 401 ends the session`() {
        val session = FakeSession("old")
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(SignedOutException::class.java) {
            client(session).newCall(Request.Builder().url(server.url("/api/wallet")).build()).execute()
        }
        assertNull(session.currentToken())
    }

    @Test
    fun `signing in with a wrong password is a refusal, not a session ending`() {
        val session = FakeSession(null)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"email or password is incorrect"}"""))
        client(session).newCall(Request.Builder().url(server.url("/api/login")).build()).execute().use { assertEquals(401, it.code) }
    }
}

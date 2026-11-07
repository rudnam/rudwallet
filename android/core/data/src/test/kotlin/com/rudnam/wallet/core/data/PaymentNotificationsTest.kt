package com.rudnam.wallet.core.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.data.notify.PaymentNotificationOutbox
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkIncomingNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PaymentNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class Session(token: String?) : SessionStore {
        override val token = MutableStateFlow(token)
        override fun currentToken() = token.value
        override fun save(token: String) { this.token.value = token }
        override fun clear() { token.value = null }
        override fun keepGoogleVerifier(verifier: String) = Unit
        override fun takeGoogleVerifier(): String? = null
    }

    /** A server that takes notifications (or is out of reach), and nothing else. */
    private class Server : WalletNetworkDataSource by unused() {
        val got = mutableListOf<List<NetworkIncomingNotification>>()
        var offline = false
        override suspend fun sendNotifications(notifications: List<NetworkIncomingNotification>) {
            if (offline) throw IOException("offline")
            got += notifications
        }

        companion object {
            fun unused(): WalletNetworkDataSource =
                Proxy.newProxyInstance(WalletNetworkDataSource::class.java.classLoader, arrayOf(WalletNetworkDataSource::class.java)) { _, m, _ -> error("not used: ${m.name}") } as WalletNetworkDataSource
        }
    }

    @Test
    fun `kept until sent, an update replaces one waiting, and old ones go`() = runTest {
        val server = Server()
        val outbox = PaymentNotificationOutbox(context, server, Session("t"), clock)
        val day = 24 * 60 * 60 * 1000L
        outbox.add("jp.ne.paypay.android.app", now.toEpochMilli() - 8 * day, "PayPay", "old")
        assertEquals(listOf("old"), outbox.waiting.map { it.text })
        // Adding drops what's over a week old; an update of one waiting replaces it.
        outbox.add("jp.ne.paypay.android.app", now.toEpochMilli(), "PayPay", "¥480")
        outbox.add("jp.ne.paypay.android.app", now.toEpochMilli(), "PayPay", "ドトール ¥480")
        outbox.add("com.google.android.apps.walletnfcrel", now.toEpochMilli() + 1, "Visa", "¥1,280")
        assertEquals(listOf("ドトール ¥480", "¥1,280"), outbox.waiting.map { it.text })

        server.offline = true
        assertEquals(PaymentNotificationOutbox.Sent.Offline, outbox.send())
        assertEquals(2, outbox.waiting.size)
        server.offline = false
        assertEquals(PaymentNotificationOutbox.Sent.Done, outbox.send())
        assertEquals(0, outbox.waiting.size)
        assertEquals(2, server.got.single().size)
    }

    @Test
    fun `signed out, they wait`() = runTest {
        val outbox = PaymentNotificationOutbox(context, Server(), Session(null), clock)
        outbox.add("jp.ne.paypay.android.app", now.toEpochMilli(), "PayPay", "¥480")
        assertEquals(PaymentNotificationOutbox.Sent.SignedOut, outbox.send())
        assertEquals(1, outbox.waiting.size)
    }

    @Test
    fun `what the Java listener left is read as it was`() {
        context.getSharedPreferences("payment-notifications", Context.MODE_PRIVATE).edit {
            putString("queue", """[{"package":"jp.ne.paypay.android.app","when":1763078400000,"title":"PayPay","text":"¥480"}]""")
        }
        val outbox = PaymentNotificationOutbox(context, Server(), Session("t"), clock)
        assertEquals(NetworkIncomingNotification("jp.ne.paypay.android.app", 1763078400000, "PayPay", "¥480"), outbox.waiting.single())
    }
}

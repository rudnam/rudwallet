package com.rudnam.wallet.core.datastore

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Robolectric has no Android Keystore: a reversible stand-in that still changes what's written. */
    private val cipher = object : TokenCipher {
        override fun seal(plain: String) = "sealed:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("sealed:").takeIf { it != sealed }?.reversed()
    }

    @Test
    fun `a session from the build with the web view moves here, and nobody is signed out`() = runTest {
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit { putString("session", "t0k3n").putString("server", "https://x") }
        val store = EncryptedSessionStore(context, cipher)
        assertEquals("t0k3n", store.currentToken())
        assertEquals("t0k3n", store.token.first())
        // Gone from where the web view kept it, and not kept here in the clear.
        assertFalse(context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).contains("session"))
        assertEquals("sealed:n3k0t", context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("token", null))
        // A new start reads it back.
        assertEquals("t0k3n", EncryptedSessionStore(context, cipher).currentToken())
    }

    @Test
    fun `signing in and out, and Google's secret taken once`() = runTest {
        val store = EncryptedSessionStore(context, cipher)
        assertNull(store.currentToken())
        store.save("new")
        assertEquals("new", store.token.first())
        store.clear()
        assertNull(store.token.first())
        store.keepGoogleVerifier("v3r1f13r")
        assertEquals("v3r1f13r", store.takeGoogleVerifier())
        assertNull(store.takeGoogleVerifier())
    }

    @Test
    fun `a token that can't be opened (restored to another phone) is no session`() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit { putString("token", "garbage") }
        assertNull(EncryptedSessionStore(context, cipher).currentToken())
    }
}

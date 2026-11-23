package com.rudnam.wallet.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.rudnam.wallet.core.datastore.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Losing notification access after it worked is said with one notification, until it's back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AccessWatchTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val session = FakeSession("token")
    private val watch = AccessWatch(context, session)
    private val shown get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Before
    fun allowNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun access(on: Boolean) {
        val listener = ComponentName(context, PaymentListener::class.java).flattenToString()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", if (on) listener else "")
    }

    @Test
    fun `access lost after it worked warns once, and again after it comes back and goes`() {
        access(true)
        watch.check()
        assertEquals(0, shown)

        access(false)
        watch.check()
        watch.check()
        assertEquals(1, shown)

        access(true)
        watch.check()
        assertEquals(0, shown) // the warning goes once it's back

        access(false)
        watch.check()
        assertEquals(1, shown)
    }

    @Test
    fun `no warning for access that never worked, or when signed out`() {
        access(false)
        watch.check()
        assertEquals(0, shown)

        access(true)
        watch.check()
        access(false)
        session.signedIn = null
        watch.check()
        assertEquals(0, shown)
    }

    private class FakeSession(var signedIn: String?) : SessionStore {
        override val token = MutableStateFlow(signedIn)
        override fun currentToken() = signedIn
        override fun save(token: String) { signedIn = token }
        override fun clear() { signedIn = null }
        override fun keepGoogleVerifier(verifier: String) {}
        override fun takeGoogleVerifier(): String? = null
    }
}

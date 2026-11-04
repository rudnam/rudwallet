package com.rudnam.wallet.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Who is signed in: the session token the server gave, if any. */
interface SessionStore {
    /** The token, or null when nobody is signed in; follows sign-in and sign-out as they happen. */
    val token: Flow<String?>

    /** The token now, for a request about to go out. */
    fun currentToken(): String?

    /** Forgets the session, e.g. when the server has ended it. */
    fun clear()
}

/**
 * The session as the web app keeps it, in Capacitor's preferences (the
 * SharedPreferences "CapacitorStorage", key "session"; web/src/native.ts).
 * Signing in and out still happens in the web app until sign-in is native,
 * so for now this reads and clears what it wrote. The widgets and the
 * payment notification listener read the same place.
 */
@Singleton
class CapacitorSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) : SessionStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

    override val token: Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY) trySend(currentToken())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(currentToken())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override fun currentToken(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }

    override fun clear() {
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val KEY = "session"
    }
}

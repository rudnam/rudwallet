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

    /** Keeps a new session's token (signing in). */
    fun save(token: String)

    /** Forgets the session, e.g. when the server has ended it. */
    fun clear()

    /**
     * Google sign-in's secret, kept while the phone's browser is open (the
     * app may be stopped meanwhile), and taken once when it comes back.
     */
    fun keepGoogleVerifier(verifier: String)

    fun takeGoogleVerifier(): String?
}

/**
 * The session, where the web app has always kept it: Capacitor's
 * preferences (the SharedPreferences "CapacitorStorage", key "session";
 * web/src/native.ts), app-private. The widgets and the payment notification
 * listener read the same place, and so does the web view for the pages it
 * still shows; it moves once they're gone.
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

    override fun save(token: String) {
        prefs.edit { putString(KEY, token) }
    }

    override fun clear() {
        prefs.edit { remove(KEY) }
    }

    override fun keepGoogleVerifier(verifier: String) {
        prefs.edit { putString(VERIFIER, verifier) }
    }

    override fun takeGoogleVerifier(): String? = prefs.getString(VERIFIER, null).also { prefs.edit { remove(VERIFIER) } }

    private companion object {
        const val KEY = "session"
        const val VERIFIER = "google-verifier"
    }
}

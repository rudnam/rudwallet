package com.rudnam.wallet.core.datastore

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

/** Seals and opens what's kept on the phone. */
interface TokenCipher {
    fun seal(plain: String): String

    /** Null if it can't be opened (the key is gone, say, after a restore to another phone). */
    fun open(sealed: String): String?
}

/**
 * AES-GCM with a key in the Android Keystore, which never leaves it: a copy
 * of the app's files (a backup, another phone) can't open the token.
 */
internal class KeystoreCipher @Inject constructor() : TokenCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * The session, sealed with a Keystore key ([TokenCipher]) in the app's own
 * preferences. A phone updated from a build with the web view had the token
 * in Capacitor's preferences ("CapacitorStorage", key "session"): it's moved
 * here the first time, and removed there, so nobody is signed out.
 */
@Singleton
class EncryptedSessionStore @Inject internal constructor(
    @ApplicationContext context: Context,
    private val cipher: TokenCipher,
) : SessionStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // The token, opened once and kept in memory: every request asks for it.
    @Volatile private var cached: String? = null

    @Volatile private var loaded = false

    init {
        val old = context.getSharedPreferences(OLD_FILE, Context.MODE_PRIVATE)
        old.getString(OLD_KEY, null)?.takeIf { it.isNotEmpty() && !prefs.contains(KEY) }?.let { save(it) }
        if (old.contains(OLD_KEY) || old.contains(OLD_VERIFIER)) old.edit { remove(OLD_KEY).remove(OLD_VERIFIER).remove("server") }
    }

    override val token: Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY) trySend(currentToken())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(currentToken())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override fun currentToken(): String? {
        if (!loaded) {
            cached = prefs.getString(KEY, null)?.let(cipher::open)
            loaded = true
        }
        return cached
    }

    override fun save(token: String) {
        cached = token
        loaded = true
        prefs.edit { putString(KEY, cipher.seal(token)) }
    }

    override fun clear() {
        cached = null
        loaded = true
        prefs.edit { remove(KEY) }
    }

    override fun keepGoogleVerifier(verifier: String) {
        prefs.edit { putString(VERIFIER, cipher.seal(verifier)) }
    }

    override fun takeGoogleVerifier(): String? = prefs.getString(VERIFIER, null)?.let(cipher::open).also { prefs.edit { remove(VERIFIER) } }

    private companion object {
        const val FILE = "session"
        const val KEY = "token"
        const val VERIFIER = "google-verifier"
        const val OLD_FILE = "CapacitorStorage"
        const val OLD_KEY = "session"
        const val OLD_VERIFIER = "google-verifier"
    }
}

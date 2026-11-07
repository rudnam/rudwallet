package com.rudnam.wallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.rudnam.wallet.core.model.Me
import javax.inject.Inject
import javax.inject.Singleton

/** Light, dark, or whatever the phone uses. */
enum class Appearance(val key: String) {
    System("auto"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromKey(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: System
    }
}

data class UserPreferences(
    val appearance: Appearance,
    /** Wallet's first-run checklist was put away. */
    val setupDismissed: Boolean,
    /** The newest update the user said "Later" to: not offered again until a newer one. */
    val updateLaterVersion: Int,
    /** The account the last record was added to: a new one starts there. */
    val lastAccountId: Long? = null,
)

/** The user's choices about this phone's app, kept on the phone (not part of the wallet). */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { p ->
        UserPreferences(
            appearance = Appearance.fromKey(p[APPEARANCE]),
            setupDismissed = p[SETUP_DISMISSED] ?: false,
            updateLaterVersion = p[UPDATE_LATER] ?: 0,
            lastAccountId = p[LAST_ACCOUNT],
        )
    }

    suspend fun setAppearance(appearance: Appearance) {
        dataStore.edit { it[APPEARANCE] = appearance.key }
    }

    suspend fun dismissSetup() {
        dataStore.edit { it[SETUP_DISMISSED] = true }
    }

    suspend fun updateLater(versionCode: Int) {
        dataStore.edit { it[UPDATE_LATER] = versionCode }
    }

    suspend fun setLastAccount(id: Long) {
        dataStore.edit { it[LAST_ACCOUNT] = id }
    }

    /** Who was signed in when the server last said, so Settings can show it offline; null once they sign out. */
    val me: Flow<Me?> = dataStore.data.map { p ->
        p[ME_EMAIL]?.let { Me(it, hasPassword = p[ME_PASSWORD] ?: true, demo = p[ME_DEMO] ?: false, confirmEmail = p[ME_CONFIRM] ?: false) }
    }

    suspend fun setMe(me: Me?) {
        dataStore.edit {
            if (me == null) {
                it.remove(ME_EMAIL)
                it.remove(ME_PASSWORD)
                it.remove(ME_DEMO)
                it.remove(ME_CONFIRM)
            } else {
                it[ME_EMAIL] = me.email
                it[ME_PASSWORD] = me.hasPassword
                it[ME_DEMO] = me.demo
                it[ME_CONFIRM] = me.confirmEmail
            }
        }
    }

    private companion object {
        val LAST_ACCOUNT = longPreferencesKey("last_account")
        val ME_EMAIL = stringPreferencesKey("me_email")
        val ME_PASSWORD = booleanPreferencesKey("me_has_password")
        val ME_DEMO = booleanPreferencesKey("me_demo")
        val ME_CONFIRM = booleanPreferencesKey("me_confirm_email")
        val APPEARANCE = stringPreferencesKey("appearance")
        val SETUP_DISMISSED = booleanPreferencesKey("setup_dismissed")
        val UPDATE_LATER = intPreferencesKey("update_later")
    }
}

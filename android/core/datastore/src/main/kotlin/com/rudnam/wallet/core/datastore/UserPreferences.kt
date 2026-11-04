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

    private companion object {
        val LAST_ACCOUNT = longPreferencesKey("last_account")
        val APPEARANCE = stringPreferencesKey("appearance")
        val SETUP_DISMISSED = booleanPreferencesKey("setup_dismissed")
        val UPDATE_LATER = intPreferencesKey("update_later")
    }
}

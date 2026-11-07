package com.rudnam.wallet.core.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import java.io.File

/** The user's preferences in a DataStore of their own, in a temporary file. */
class TestUserPreferences {
    private val file: File = File.createTempFile("prefs", "").apply { delete(); mkdirs() }.resolve("test.preferences_pb")

    val repository = UserPreferencesRepository(PreferenceDataStoreFactory.create { file })
}

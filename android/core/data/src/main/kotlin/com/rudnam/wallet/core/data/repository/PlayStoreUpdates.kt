package com.rudnam.wallet.core.data.repository

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.rudnam.wallet.core.model.AppUpdate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates for a copy installed from the Play Store, through Google's in-app
 * updates library: whether Play has a newer build for this phone, and Play's
 * own full-screen update (it downloads, installs and reopens the app).
 * Play only offers what the phone's track has, so a release shows up here a
 * few minutes after the Play upload, once Play has processed it.
 */
@Singleton
class PlayStoreUpdates @Inject constructor(private val play: AppUpdateManager) {
    /**
     * A newer build Play can install now, unless "Later" was chosen for it
     * ([skipped]); also an update that was started and interrupted, to finish it.
     */
    suspend fun available(skipped: Int): AppUpdate? {
        val info = info() ?: return null // offline, or Play isn't there: the next check will tell
        val version = info.availableVersionCode()
        return when {
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> fromPlay(version)
            info.installable() && version > skipped -> fromPlay(version)
            else -> null
        }
    }

    /** Opens Play's update screen over [activity]; false if Play has nothing to install after all. */
    suspend fun start(activity: Activity): Boolean {
        val info = info() ?: return false
        if (info.updateAvailability() != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS && !info.installable()) return false
        play.startUpdateFlow(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
        return true
    }

    private suspend fun info(): AppUpdateInfo? = try {
        play.requestAppUpdateInfo()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun AppUpdateInfo.installable() =
        updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

    // Play gives no version name or download address.
    private fun fromPlay(version: Int) = AppUpdate(version, versionName = "", url = "", fromPlay = true)
}

@Module
@InstallIn(SingletonComponent::class)
internal object PlayModule {
    @Provides
    @Singleton
    fun appUpdateManager(@ApplicationContext context: Context): AppUpdateManager = AppUpdateManagerFactory.create(context)
}

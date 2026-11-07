package com.rudnam.wallet.core.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.AppUpdate
import com.rudnam.wallet.core.network.Server
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the server offers a newer build than this one (GET /api/downloads
 * reads the APK's versionCode). A copy from the Play Store is left to the
 * Play Store; "Later" hides an update until a newer one.
 */
interface UpdateRepository {
    suspend fun check(): AppUpdate?

    suspend fun later(update: AppUpdate)
}

@Singleton
internal class ServerUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: WalletNetworkDataSource,
    private val preferences: UserPreferencesRepository,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
    private val server: Server,
) : UpdateRepository {
    override suspend fun check(): AppUpdate? = withContext(io) {
        if (installer() == "com.android.vending") return@withContext null
        val apk = try {
            network.downloads().android
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // offline: the next check will tell
        } ?: return@withContext null
        val offered = apk.version ?: return@withContext null
        val skipped = preferences.preferences.first().updateLaterVersion
        if (offered <= installedVersion() || offered <= skipped) return@withContext null
        AppUpdate(offered, apk.versionName.orEmpty(), server.resolve(apk.url))
    }

    override suspend fun later(update: AppUpdate) = preferences.updateLater(update.versionCode)

    private fun installedVersion(): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    @Suppress("DEPRECATION")
    private fun installer(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

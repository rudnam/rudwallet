package com.rudnam.wallet.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.rudnam.wallet.core.data.repository.PlayStoreUpdates
import com.rudnam.wallet.core.data.repository.ServerUpdateRepository
import com.rudnam.wallet.core.network.Server
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkApk
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.testing.TestUserPreferences
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

/** Which update is offered: the server's APK, or for a Play install, Play's build named after the server's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val play = FakeAppUpdateManager(context)

    /** The server's GET /api/downloads, remembering the `since` it was asked with. */
    private class Downloads(var apk: NetworkApk? = null) : WalletNetworkDataSource by unused() {
        var offline = false
        val asked = mutableListOf<Int?>()
        override suspend fun downloads(since: Int?): NetworkDownloads {
            asked += since
            if (offline) throw IOException("offline")
            return NetworkDownloads(apk)
        }

        companion object {
            fun unused(): WalletNetworkDataSource =
                Proxy.newProxyInstance(WalletNetworkDataSource::class.java.classLoader, arrayOf(WalletNetworkDataSource::class.java)) { _, m, _ -> error("not used: ${m.name}") } as WalletNetworkDataSource
        }
    }

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        Shadows.shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
            versionCode = 40
            longVersionCode = 40L
        }
    }

    private fun repo(network: WalletNetworkDataSource, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = ServerUpdateRepository(
        context = context,
        network = network,
        preferences = TestUserPreferences().repository,
        io = dispatcher,
        server = Server(),
        play = PlayStoreUpdates(play),
    )

    private fun fromPlayStore() =
        Shadows.shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, "com.android.vending", "com.android.vending")

    @Test
    fun `the server's newer APK is offered`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = Downloads(NetworkApk("/download/wallet.apk", 42, "2.1.42"))

        val update = repo(downloads, UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals("2.1.42", update.versionName)
        assertEquals(listOf<Int?>(40), downloads.asked)
    }

    @Test
    fun `a Play Store update takes the server's name for the same build`() = runTest(UnconfinedTestDispatcher()) {
        fromPlayStore()
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        val downloads = Downloads(NetworkApk("/download/wallet.apk", 42, "2.1.42"))

        val update = repo(downloads, UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals(true, update.fromPlay)
        assertEquals("2.1.42", update.versionName)
    }

    @Test
    fun `a Play Store update is still offered when the server can't be reached`() = runTest(UnconfinedTestDispatcher()) {
        fromPlayStore()
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        val downloads = Downloads().apply { offline = true }

        val update = repo(downloads, UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals("", update.versionName)
    }
}

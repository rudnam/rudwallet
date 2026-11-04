package com.rudnam.wallet.core.network.retrofit

import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** README.md's API table: the parts the native screens use so far. */
internal interface WalletApi {
    @GET("api/wallet")
    suspend fun wallet(): NetworkWallet

    @GET("api/transactions")
    suspend fun transactions(@Query("from") from: String, @Query("to") to: String): List<NetworkTransaction>

    @GET("api/budgets")
    suspend fun budgets(): List<NetworkBudget>

    @GET("api/notifications")
    suspend fun notifications(): NetworkNotifications

    @GET("api/downloads")
    suspend fun downloads(): NetworkDownloads
}

@Singleton
internal class RetrofitWalletNetwork @Inject constructor(retrofit: Retrofit) : WalletNetworkDataSource {
    private val api = retrofit.create(WalletApi::class.java)

    override suspend fun wallet() = api.wallet()

    override suspend fun transactions(from: Instant, to: Instant) = api.transactions(rfc3339(from), rfc3339(to))

    override suspend fun budgets() = api.budgets()

    override suspend fun notifications() = api.notifications()

    override suspend fun downloads() = api.downloads()

    /** In the phone's time zone, as the web app sends it, e.g. 2026-11-01T00:00:00+09:00. */
    private fun rfc3339(t: Instant): String =
        OffsetDateTime.ofInstant(t, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

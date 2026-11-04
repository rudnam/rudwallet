package com.rudnam.wallet.core.network.retrofit

import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkBulkChange
import com.rudnam.wallet.core.network.model.NetworkBulkResult
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkError
import com.rudnam.wallet.core.network.model.NetworkIds
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkRecordInput
import com.rudnam.wallet.core.network.model.NetworkTag
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
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
    suspend fun transactions(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("status") status: String? = null,
        @Query("account_id") accountId: Long? = null,
        @Query("category_id") categoryId: String? = null,
        @Query("q") query: String? = null,
        @Query("tag") tag: String? = null,
    ): List<NetworkTransaction>

    @POST("api/transactions/bulk")
    suspend fun bulk(@Body change: NetworkBulkChange): NetworkBulkResult

    @POST("api/transactions")
    suspend fun createTransaction(@Body input: NetworkRecordInput): NetworkTransaction

    @PUT("api/transactions/{id}")
    suspend fun updateTransaction(@Path("id") id: Long, @Body input: NetworkRecordInput): NetworkTransaction

    @DELETE("api/transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Long): Response<Unit>

    @POST("api/transactions/restore")
    suspend fun restoreTransactions(@Body ids: NetworkIds): Response<Unit>

    @GET("api/tags")
    suspend fun tags(): List<NetworkTag>

    @GET("api/budgets")
    suspend fun budgets(): List<NetworkBudget>

    @GET("api/notifications")
    suspend fun notifications(): NetworkNotifications

    @GET("api/downloads")
    suspend fun downloads(): NetworkDownloads
}

@Singleton
internal class RetrofitWalletNetwork @Inject constructor(retrofit: Retrofit, private val json: Json) : WalletNetworkDataSource {
    private val api = retrofit.create(WalletApi::class.java)

    override suspend fun wallet() = call { api.wallet() }

    override suspend fun transactions(from: Instant, to: Instant) = call { api.transactions(rfc3339(from), rfc3339(to)) }

    override suspend fun confirmedSince(from: Instant) = call { api.transactions(rfc3339(from), status = "confirmed") }

    override suspend fun createRecord(input: NetworkRecordInput) = call { api.createTransaction(input) }

    override suspend fun updateRecord(id: Long, input: NetworkRecordInput) = call { api.updateTransaction(id, input) }

    override suspend fun deleteRecord(id: Long) = call { api.deleteTransaction(id).orThrow() }

    override suspend fun restoreRecords(ids: List<Long>) = call { api.restoreTransactions(NetworkIds(ids)).orThrow() }

    override suspend fun tags() = call { api.tags() }

    override suspend fun search(
        from: Instant?, to: Instant?, accountId: Long?, categoryId: String?, query: String?, tag: String?, status: String?,
    ) = call { api.transactions(from?.let(::rfc3339), to?.let(::rfc3339), status, accountId, categoryId, query, tag) }

    override suspend fun bulk(change: NetworkBulkChange) = call { api.bulk(change) }

    override suspend fun budgets() = call { api.budgets() }

    override suspend fun notifications() = call { api.notifications() }

    override suspend fun downloads() = call { api.downloads() }

    private fun Response<Unit>.orThrow() {
        if (!isSuccessful) throw HttpException(this)
    }

    /** A refusal becomes an [ApiException] with the server's message for the user (internal/httpx). */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val body = e.response()?.errorBody()?.string().orEmpty()
        val message = runCatching { json.decodeFromString<NetworkError>(body).error }.getOrNull().orEmpty()
        throw ApiException(e.code(), message.ifEmpty { e.message() })
    }

    /** In the phone's time zone, as the web app sends it, e.g. 2026-11-01T00:00:00+09:00. */
    private fun rfc3339(t: Instant): String =
        OffsetDateTime.ofInstant(t, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

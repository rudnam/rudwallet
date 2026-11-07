package com.rudnam.wallet.core.network.retrofit

import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkAccountInput
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkCategory
import com.rudnam.wallet.core.network.model.NetworkCategoryInput
import com.rudnam.wallet.core.network.model.NetworkDeleteMe
import com.rudnam.wallet.core.network.model.NetworkCardRuleInput
import com.rudnam.wallet.core.network.model.NetworkFileImport
import com.rudnam.wallet.core.network.model.NetworkICCardImport
import com.rudnam.wallet.core.network.model.NetworkImportPreview
import com.rudnam.wallet.core.network.model.NetworkImportResult
import com.rudnam.wallet.core.network.model.NetworkIncomingNotification
import com.rudnam.wallet.core.network.model.NetworkIncomingNotifications
import com.rudnam.wallet.core.network.model.NetworkMcpToken
import com.rudnam.wallet.core.network.model.NetworkWidgetSummary
import com.rudnam.wallet.core.network.model.NetworkAppCode
import com.rudnam.wallet.core.network.model.NetworkAuthOptions
import com.rudnam.wallet.core.network.model.NetworkCredentials
import com.rudnam.wallet.core.network.model.NetworkDemo
import com.rudnam.wallet.core.network.model.NetworkEmail
import com.rudnam.wallet.core.network.model.NetworkMe
import com.rudnam.wallet.core.network.model.NetworkName
import com.rudnam.wallet.core.network.model.NetworkPayeeRuleInput
import com.rudnam.wallet.core.network.model.NetworkRules
import com.rudnam.wallet.core.network.model.NetworkSettingsInput
import com.rudnam.wallet.core.network.model.NetworkBudgetInput
import com.rudnam.wallet.core.network.model.NetworkBulkChange
import com.rudnam.wallet.core.network.model.NetworkBulkResult
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkError
import com.rudnam.wallet.core.network.model.NetworkIds
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkReconcile
import com.rudnam.wallet.core.network.model.NetworkReconcileResult
import com.rudnam.wallet.core.network.model.NetworkRecordInput
import com.rudnam.wallet.core.network.model.NetworkStats
import com.rudnam.wallet.core.network.model.NetworkTag
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
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

    @POST("api/accounts/{id}/reconcile")
    suspend fun reconcile(@Path("id") id: Long, @Body body: NetworkReconcile): NetworkReconcileResult

    @PUT("api/accounts/order")
    suspend fun setAccountOrder(@Body ids: NetworkIds): Response<Unit>

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

    @POST("api/budgets")
    suspend fun createBudget(@Body input: NetworkBudgetInput): NetworkBudget

    @PUT("api/budgets/{id}")
    suspend fun updateBudget(@Path("id") id: Long, @Body input: NetworkBudgetInput): NetworkBudget

    @DELETE("api/budgets/{id}")
    suspend fun deleteBudget(@Path("id") id: Long): Response<Unit>

    @GET("api/stats")
    suspend fun stats(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("tz") zone: String,
        @Query("account_id") accountId: Long? = null,
    ): NetworkStats

    @GET("api/notifications")
    suspend fun notifications(): NetworkNotifications

    @GET("api/widget")
    suspend fun widgetSummary(@Query("from") from: String, @Query("to") to: String): NetworkWidgetSummary

    @POST("api/notifications")
    suspend fun sendNotifications(@Body body: NetworkIncomingNotifications): Response<Unit>

    @GET("api/downloads")
    suspend fun downloads(): NetworkDownloads

    @GET("api/me")
    suspend fun me(): NetworkMe

    @GET("api/auth/options")
    suspend fun authOptions(): NetworkAuthOptions

    @POST("api/login")
    suspend fun login(@Body credentials: NetworkCredentials): NetworkMe

    @POST("api/signup")
    suspend fun signup(@Body credentials: NetworkCredentials): NetworkMe

    @POST("api/demo")
    suspend fun startDemo(@Body demo: NetworkDemo): NetworkMe

    @POST("api/password/forgot")
    suspend fun forgotPassword(@Body email: NetworkEmail): Response<Unit>

    @POST("api/auth/app-token")
    suspend fun appToken(@Body code: NetworkAppCode): NetworkMe

    @POST("api/verify-email")
    suspend fun resendConfirmation(): Response<Unit>

    @POST("api/logout")
    suspend fun logout(): Response<Unit>

    @HTTP(method = "DELETE", path = "api/me", hasBody = true)
    suspend fun deleteMe(@Body confirm: NetworkDeleteMe): Response<Unit>

    @PUT("api/settings")
    suspend fun updateSettings(@Body input: NetworkSettingsInput): Response<Unit>

    @POST("api/accounts")
    suspend fun createAccount(@Body input: NetworkAccountInput): Response<Unit>

    @PUT("api/accounts/{id}")
    suspend fun updateAccount(@Path("id") id: Long, @Body input: NetworkAccountInput): Response<Unit>

    @DELETE("api/accounts/{id}")
    suspend fun deleteAccount(@Path("id") id: Long): Response<Unit>

    @GET("api/categories/missing")
    suspend fun missingCategories(): List<NetworkCategory>

    @POST("api/categories")
    suspend fun createCategory(@Body input: NetworkCategoryInput): Response<Unit>

    @PUT("api/categories/{id}")
    suspend fun updateCategory(@Path("id") id: Long, @Body input: NetworkCategoryInput): Response<Unit>

    @DELETE("api/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Long): Response<Unit>

    @GET("api/rules")
    suspend fun rules(): NetworkRules

    @PUT("api/rules/payees")
    suspend fun setPayeeRule(@Body input: NetworkPayeeRuleInput): Response<Unit>

    @DELETE("api/rules/payees")
    suspend fun deletePayeeRule(@Query("payee") payee: String): Response<Unit>

    @PUT("api/rules/cards")
    suspend fun setCardRule(@Body input: NetworkCardRuleInput): Response<Unit>

    @DELETE("api/rules/cards")
    suspend fun deleteCardRule(@Query("source") source: String): Response<Unit>

    @GET("api/mcp/tokens")
    suspend fun mcpTokens(): List<NetworkMcpToken>

    @POST("api/mcp/tokens")
    suspend fun createMcpToken(@Body name: NetworkName): NetworkMcpToken

    @DELETE("api/mcp/tokens/{id}")
    suspend fun deleteMcpToken(@Path("id") id: Long): Response<Unit>

    @POST("api/import/{source}?preview=1")
    suspend fun previewImport(@Path("source") source: String, @Body csv: RequestBody, @Query("tz_offset") tzOffset: Int): NetworkImportPreview

    @POST("api/import/{source}")
    suspend fun import(@Path("source") source: String, @Body body: NetworkFileImport, @Query("tz_offset") tzOffset: Int): NetworkImportResult

    @POST("api/import/ic-card?preview=1")
    suspend fun previewICCard(@Body body: NetworkICCardImport): NetworkImportPreview

    @POST("api/import/ic-card")
    suspend fun importICCard(@Body body: NetworkICCardImport): NetworkImportResult
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

    override suspend fun reconcile(accountId: Long, balance: Long) = call { api.reconcile(accountId, NetworkReconcile(balance)) }

    override suspend fun setAccountOrder(ids: List<Long>) = call { api.setAccountOrder(NetworkIds(ids)).orThrow() }

    override suspend fun budgets() = call { api.budgets() }

    override suspend fun createBudget(input: NetworkBudgetInput) = call { api.createBudget(input) }

    override suspend fun updateBudget(id: Long, input: NetworkBudgetInput) = call { api.updateBudget(id, input) }

    override suspend fun deleteBudget(id: Long) = call { api.deleteBudget(id).orThrow() }

    override suspend fun stats(from: Instant, to: Instant, zone: String, accountId: Long?) =
        call { api.stats(rfc3339(from), rfc3339(to), zone, accountId) }

    override suspend fun notifications() = call { api.notifications() }

    override suspend fun widgetSummary(from: Instant, to: Instant) = call { api.widgetSummary(rfc3339(from), rfc3339(to)) }

    override suspend fun sendNotifications(notifications: List<NetworkIncomingNotification>) =
        call { api.sendNotifications(NetworkIncomingNotifications(notifications)).orThrow() }

    override suspend fun me() = call { api.me() }

    override suspend fun authOptions() = call { api.authOptions() }

    override suspend fun login(email: String, password: String) = call { api.login(NetworkCredentials(email, password)) }

    override suspend fun signup(email: String, password: String) = call { api.signup(NetworkCredentials(email, password)) }

    override suspend fun startDemo(zone: String) = call { api.startDemo(NetworkDemo(zone)) }

    override suspend fun forgotPassword(email: String) = call { api.forgotPassword(NetworkEmail(email)).orThrow() }

    override suspend fun appToken(code: String, verifier: String) = call { api.appToken(NetworkAppCode(code, verifier)) }

    override suspend fun resendConfirmation() = call { api.resendConfirmation().orThrow() }

    override suspend fun logout() = call { api.logout().orThrow() }

    override suspend fun deleteMe(confirm: NetworkDeleteMe) = call { api.deleteMe(confirm).orThrow() }

    override suspend fun updateSettings(input: NetworkSettingsInput) = call { api.updateSettings(input).orThrow() }

    override suspend fun createAccount(input: NetworkAccountInput) = call { api.createAccount(input).orThrow() }

    override suspend fun updateAccount(id: Long, input: NetworkAccountInput) = call { api.updateAccount(id, input).orThrow() }

    override suspend fun deleteAccount(id: Long) = call { api.deleteAccount(id).orThrow() }

    override suspend fun missingCategories() = call { api.missingCategories() }

    override suspend fun createCategory(input: NetworkCategoryInput) = call { api.createCategory(input).orThrow() }

    override suspend fun updateCategory(id: Long, input: NetworkCategoryInput) = call { api.updateCategory(id, input).orThrow() }

    override suspend fun deleteCategory(id: Long) = call { api.deleteCategory(id).orThrow() }

    override suspend fun rules() = call { api.rules() }

    override suspend fun setPayeeRule(payee: String, categoryId: Long) = call { api.setPayeeRule(NetworkPayeeRuleInput(payee, categoryId)).orThrow() }

    override suspend fun deletePayeeRule(payee: String) = call { api.deletePayeeRule(payee).orThrow() }

    override suspend fun setCardRule(source: String, accountId: Long) = call { api.setCardRule(NetworkCardRuleInput(source, accountId)).orThrow() }

    override suspend fun deleteCardRule(source: String) = call { api.deleteCardRule(source).orThrow() }

    override suspend fun mcpTokens() = call { api.mcpTokens() }

    override suspend fun createMcpToken(name: String) = call { api.createMcpToken(NetworkName(name)) }

    override suspend fun deleteMcpToken(id: Long) = call { api.deleteMcpToken(id).orThrow() }

    override suspend fun previewImport(source: String, csv: String, tzOffset: Int) =
        call { api.previewImport(source, csv.toRequestBody("text/csv; charset=utf-8".toMediaType()), tzOffset) }

    override suspend fun import(source: String, body: NetworkFileImport, tzOffset: Int) = call { api.import(source, body, tzOffset) }

    override suspend fun previewICCard(body: NetworkICCardImport) = call { api.previewICCard(body) }

    override suspend fun importICCard(body: NetworkICCardImport) = call { api.importICCard(body) }

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

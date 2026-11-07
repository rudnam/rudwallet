package com.rudnam.wallet.core.network

import androidx.core.os.LocaleListCompat
import com.rudnam.wallet.core.datastore.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Signs every request in as the Android app (internal/account/app.go): the
 * session's bearer token and X-Wallet-Client. Accept-Language is the app's
 * language, which new categories are named in. A 401 means the session has
 * ended: it's forgotten, and the call fails with [SignedOutException].
 */
internal class AuthInterceptor(
    private val session: SessionStore,
    private val language: () -> LocaleListCompat,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Wallet-Client", "android")
            .header("Accept", "application/json")
            .header("Accept-Language", language().toLanguageTags().ifEmpty { "en" })
        val token = session.currentToken()
        token?.let { request.header("Authorization", "Bearer $it") }
        val response = chain.proceed(request.build())
        // Only a request made with a session can find it ended: signing in with a wrong password is a 401 too.
        if (response.code == 401 && token != null && !chain.request().url.encodedPath.endsWith("/api/downloads")) {
            response.close()
            session.clear()
            throw SignedOutException()
        }
        return response
    }
}

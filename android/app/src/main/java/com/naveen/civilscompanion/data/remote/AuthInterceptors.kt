package com.naveen.civilscompanion.data.remote

import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.data.remote.dto.RefreshRequest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Adds the login token to every request that needs it. */
@Singleton
class BearerInterceptor @Inject constructor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.accessToken ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** When the server says 401, quietly gets a new token with the refresh token and retries once. */
@Singleton
class TokenRefresher @Inject constructor(
    private val tokens: TokenStore,
    private val authApi: Provider<AuthApi>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        synchronized(this) {
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
            val current = tokens.accessToken
            // Another call already refreshed while we waited.
            if (current != null && current != sent) return response.request.withToken(current)

            val refresh = tokens.refreshToken ?: return null
            val result = runCatching { authApi.get().refreshBlocking(RefreshRequest(refresh)).execute() }
                .getOrNull() ?: return null
            val body = result.body()
            if (result.isSuccessful && body != null) {
                tokens.saveTokens(body.accessToken, body.refreshToken)
                return response.request.withToken(body.accessToken)
            }
            if (result.code() == 401) tokens.clearTokens() // refresh token dead: back to login
            return null
        }
    }

    private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 1
        while (r?.priorResponse != null) {
            n++
            r = r.priorResponse
        }
        return n
    }
}

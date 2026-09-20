package com.naveen.civilscompanion.data.remote

import com.naveen.civilscompanion.data.auth.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retrofit is built with a placeholder address; this points every request at the server
 * the owner typed on the login screen, so changing servers needs no rebuild.
 */
@Singleton
class ServerUrlInterceptor @Inject constructor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val target = tokens.serverUrl.toHttpUrlOrNull() ?: return chain.proceed(chain.request())
        val original = chain.request()
        val url = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(original.newBuilder().url(url).build())
    }
}

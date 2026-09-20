package com.naveen.civilscompanion.data.auth

import com.naveen.civilscompanion.data.remote.AuthApi
import com.naveen.civilscompanion.data.remote.DeviceApi
import com.naveen.civilscompanion.data.remote.dto.LoginRequest
import com.naveen.civilscompanion.data.remote.dto.RefreshRequest
import com.naveen.civilscompanion.push.PushRegistrar
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import retrofit2.HttpException

@Singleton
class AuthRepository @Inject constructor(
    private val tokens: TokenStore,
    private val authApi: AuthApi,
    private val deviceApi: DeviceApi,
    private val push: PushRegistrar,
) {
    val loggedIn: StateFlow<Boolean> get() = tokens.loggedIn
    val savedServerUrl: String get() = tokens.serverUrl

    /** Returns null on success, or a short message the owner can read. */
    suspend fun login(serverUrl: String, username: String, password: String): String? {
        val url = serverUrl.trim()
        if (!url.startsWith("https://")) return "The server address must start with https://"
        tokens.serverUrl = url
        return try {
            val t = authApi.login(LoginRequest(username.trim(), password))
            tokens.saveTokens(t.accessToken, t.refreshToken)
            push.registerCurrentToken()
            null
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> "Wrong username or password."
                429 -> "Too many attempts. Please wait a few minutes."
                else -> "The server answered with an error (${e.code()})."
            }
        } catch (e: IOException) {
            "Cannot reach the server. Check the address and your internet."
        }
    }

    suspend fun logout() {
        tokens.refreshToken?.let { runCatching { deviceApi.logout(RefreshRequest(it)) } }
        tokens.clearTokens()
    }
}

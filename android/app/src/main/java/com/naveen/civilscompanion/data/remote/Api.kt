package com.naveen.civilscompanion.data.remote

import com.naveen.civilscompanion.data.remote.dto.DeviceRequest
import com.naveen.civilscompanion.data.remote.dto.LoginRequest
import com.naveen.civilscompanion.data.remote.dto.RefreshRequest
import com.naveen.civilscompanion.data.remote.dto.TokensDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/** Calls that need no login token. */
interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokensDto

    /** Blocking version, used by the token refresher inside OkHttp. */
    @POST("auth/refresh")
    fun refreshBlocking(@Body body: RefreshRequest): Call<TokensDto>
}

/** Calls that need the login token (added automatically). */
interface DeviceApi {
    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    @POST("devices/register")
    suspend fun register(@Body body: DeviceRequest)
}

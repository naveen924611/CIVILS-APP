package com.naveen.civilscompanion.data.remote

import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.DeviceRequest
import com.naveen.civilscompanion.data.remote.dto.LoginRequest
import com.naveen.civilscompanion.data.remote.dto.RefreshRequest
import com.naveen.civilscompanion.data.remote.dto.RunBriefRequest
import com.naveen.civilscompanion.data.remote.dto.RunBriefResponse
import com.naveen.civilscompanion.data.remote.dto.SyncPullDto
import com.naveen.civilscompanion.data.remote.dto.TokensDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

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

/** Briefs, sync and alert calls (login token added automatically). */
interface SyncApi {
    @GET("sync/pull")
    suspend fun pull(@Query("since") since: String?): SyncPullDto

    @POST("briefs/run")
    suspend fun runBrief(@Body body: RunBriefRequest): RunBriefResponse

    @GET("settings/briefs")
    suspend fun briefSettings(): BriefSettingsDto

    @PUT("settings/briefs")
    suspend fun putBriefSettings(@Body body: BriefSettingsDto): BriefSettingsDto

    @POST("alerts/{id}/read")
    suspend fun markAlertRead(@Path("id") id: String)

    @POST("alerts/read-all")
    suspend fun markAllAlertsRead()
}

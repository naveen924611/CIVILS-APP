package com.naveen.civilscompanion.di

import com.naveen.civilscompanion.data.remote.AuthApi
import com.naveen.civilscompanion.data.remote.BearerInterceptor
import com.naveen.civilscompanion.data.remote.DeviceApi
import com.naveen.civilscompanion.data.remote.ServerUrlInterceptor
import com.naveen.civilscompanion.data.remote.SyncApi
import com.naveen.civilscompanion.data.remote.TokenRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // The real address is filled in by ServerUrlInterceptor.
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides @Singleton @Named("bare")
    fun bareClient(serverUrl: ServerUrlInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(serverUrl)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton @Named("authed")
    fun authedClient(
        @Named("bare") bare: OkHttpClient,
        bearer: BearerInterceptor,
        refresher: TokenRefresher,
    ): OkHttpClient = bare.newBuilder().addInterceptor(bearer).authenticator(refresher).build()

    private fun retrofit(client: OkHttpClient, json: Json) = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun authApi(@Named("bare") client: OkHttpClient, json: Json): AuthApi =
        retrofit(client, json).create(AuthApi::class.java)

    @Provides @Singleton
    fun deviceApi(@Named("authed") client: OkHttpClient, json: Json): DeviceApi =
        retrofit(client, json).create(DeviceApi::class.java)

    @Provides @Singleton
    fun syncApi(@Named("authed") client: OkHttpClient, json: Json): SyncApi =
        retrofit(client, json).create(SyncApi::class.java)
}

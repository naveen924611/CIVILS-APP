package com.naveen.civilscompanion.ui.compilation

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/** The one server call of its own: download the PDF of a month (backend/app/features/compilation/api.py). */
interface CompilationApi {
    @Streaming
    @GET("compilation/{id}/pdf")
    suspend fun pdf(@Path("id") id: String): ResponseBody
}

@Module
@InstallIn(SingletonComponent::class)
object CompilationModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun compilationApi(@Named("authed") client: OkHttpClient, json: Json): CompilationApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CompilationApi::class.java)
}

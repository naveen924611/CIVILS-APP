package com.naveen.civilscompanion.ui.answers

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** The two answer-writing calls the server offers (see backend/app/features/answers/api.py). Login token is added for us. */
interface AnswersApi {
    /** Asks the server to write a new practice question. Body: {"kind": "mains"|"short"|"essay", "topic_id": optional}. */
    @POST("answers/generate")
    suspend fun generate(@Body body: JsonObject): JsonObject

    /** Sends the photos of a handwritten answer. replace = true removes photos sent earlier (so a retry never doubles them). */
    @Multipart
    @POST("answers/{id}/images")
    suspend fun upload(
        @Path("id") id: String,
        @Query("replace") replace: Boolean,
        @Part files: List<MultipartBody.Part>,
    ): ResponseBody
}

@Module
@InstallIn(SingletonComponent::class)
object AnswersModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun answersApi(@Named("authed") client: OkHttpClient, json: Json): AnswersApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnswersApi::class.java)
}

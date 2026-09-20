package com.naveen.civilscompanion.ui.library

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/** The library's own server calls (files go up and down here; the rows themselves arrive through sync). */
interface LibraryApi {
    /** PDFs and/or pictures. Answer: {"documents":[{id,title,type,pages,processing_status,status_detail}], "problems":[...]} */
    @Multipart
    @POST("library/upload")
    suspend fun upload(@Part files: List<MultipartBody.Part>, @Part("title") title: RequestBody?): JsonObject

    /** One camera photo. Answer: {"document_id", "page", "language"} */
    @Multipart
    @POST("library/scans")
    suspend fun scan(
        @Part image: MultipartBody.Part,
        @Part("document_id") documentId: RequestBody?,
        @Part("text") text: RequestBody?,
        @Part("language") language: RequestBody?,
    ): JsonObject

    @POST("library/documents/{id}/retry")
    suspend fun retry(@Path("id") id: String): JsonObject

    @POST("library/materials/{key}/download")
    suspend fun downloadMaterial(@Path("key") key: String): JsonObject

    @Streaming
    @GET("library/documents/{id}/file")
    suspend fun file(@Path("id") id: String): ResponseBody

    @Streaming
    @GET("library/documents/{id}/pages/{page}/image")
    suspend fun pageImage(@Path("id") id: String, @Path("page") page: Int): ResponseBody
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    // The real address is filled in by ServerUrlInterceptor (same as the foundation's Retrofit clients).
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides
    @Singleton
    fun libraryApi(@Named("authed") client: OkHttpClient, json: Json): LibraryApi {
        // Big PDFs on a slow connection need more time than a normal call.
        val slow = client.newBuilder()
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(slow)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LibraryApi::class.java)
    }
}

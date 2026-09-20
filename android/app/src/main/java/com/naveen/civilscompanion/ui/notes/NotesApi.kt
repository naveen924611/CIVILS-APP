package com.naveen.civilscompanion.ui.notes

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class VersionInfo(
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val chars: Int = 0,
)

@Serializable
data class VersionList(
    val current: Int = 1,
    val versions: List<VersionInfo> = emptyList(),
)

@Serializable
data class VersionText(
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("content_md") val contentMd: String = "",
)

/** Earlier versions of a note (needs the network). Contract: backend/app/features/notes/__init__.py. */
interface NotesApi {
    @GET("notes/{id}/versions")
    suspend fun versions(@Path("id") noteId: String): VersionList

    @GET("notes/{id}/versions/{version}")
    suspend fun versionText(@Path("id") noteId: String, @Path("version") version: Int): VersionText

    @POST("notes/{id}/restore/{version}")
    suspend fun restore(@Path("id") noteId: String, @Path("version") version: Int): JsonObject
}

@Module
@InstallIn(SingletonComponent::class)
object NotesNetworkModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides
    @Singleton
    fun notesApi(@Named("authed") client: OkHttpClient, json: Json): NotesApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NotesApi::class.java)
}

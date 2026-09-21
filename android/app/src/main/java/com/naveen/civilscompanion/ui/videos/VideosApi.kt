package com.naveen.civilscompanion.ui.videos

import com.naveen.civilscompanion.data.model.Video
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class ResolveBody(
    @SerialName("url_or_id") val urlOrId: String,
    @SerialName("topic_id") val topicId: String? = null,
)

@Serializable
data class ResolveResult(
    val video: Video? = null,
    val created: Boolean = false,
    val verified: Boolean = false,
    val reason: String = "",
)

@Serializable
data class SearchHit(
    @SerialName("youtube_id") val youtubeId: String = "",
    val title: String = "",
    val channel: String = "",
)

@Serializable
data class SearchResult(
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val reason: String = "",
    val message: String = "",
)

/** Server calls of the Videos screens: check a pasted link and search YouTube (search needs a key on the server). */
interface VideosApi {
    @POST("videos/resolve")
    suspend fun resolve(@Body body: ResolveBody): ResolveResult

    @GET("videos/search")
    suspend fun search(@Query("q") q: String, @Query("topic_id") topicId: String?): SearchResult
}

@Module
@InstallIn(SingletonComponent::class)
object VideosModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun videosApi(@Named("authed") client: OkHttpClient, json: Json): VideosApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VideosApi::class.java)
}

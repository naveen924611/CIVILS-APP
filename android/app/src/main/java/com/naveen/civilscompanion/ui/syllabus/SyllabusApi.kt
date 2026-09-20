package com.naveen.civilscompanion.ui.syllabus

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Approving an imported syllabus. `tree` is the tree as the owner edited it (null = use the one saved on the server). */
@Serializable
data class ApproveBody(
    @SerialName("exam_filter") val examFilter: String?,
    @SerialName("merge_into_existing") val mergeIntoExisting: Boolean,
    val tree: JsonElement?,
)

@Serializable
data class ApproveResult(
    val created: Int = 0,
    val merged: Int = 0,
    val total: Int = 0,
    val status: String = "",
)

@Serializable
data class TreeBody(val tree: JsonElement, val title: String?)

@Serializable
data class SaveTreeResult(
    @SerialName("node_count") val nodeCount: Int = 0,
)

/**
 * Server calls of the syllabus screens (they need the network, unlike reading topics and notes which are synced).
 * Contract: backend/app/features/syllabus/__init__.py.
 */
interface SyllabusApi {
    @POST("syllabus/{id}/approve")
    suspend fun approve(@Path("id") id: String, @Body body: ApproveBody): ApproveResult

    @PUT("syllabus/{id}/tree")
    suspend fun saveTree(@Path("id") id: String, @Body body: TreeBody): SaveTreeResult
}

@Module
@InstallIn(SingletonComponent::class)
object SyllabusNetworkModule {
    // The real address is filled in by ServerUrlInterceptor (same pattern as di/NetworkModule.kt).
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides
    @Singleton
    fun syllabusApi(@Named("authed") client: OkHttpClient, json: Json): SyllabusApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SyllabusApi::class.java)
}

/** The plain sentence the server put in {"detail": "..."} (its own kind messages), or null. */
fun serverDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    return (obj["detail"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** A short kind message for a failed server call. */
fun describeError(e: Throwable): String = when (e) {
    is HttpException -> serverDetail(runCatching { e.response()?.errorBody()?.string() }.getOrNull())
        ?: "The server could not do that (error ${e.code()}). Please try again later."
    is IOException -> "Can't reach the server right now. Check your internet and try again."
    else -> "Something went wrong. Please try again."
}

/** Runs a server call and returns its result or the failure (cancellation is passed on, never swallowed). */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

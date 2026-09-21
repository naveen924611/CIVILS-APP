package com.naveen.civilscompanion.ui.settings

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
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming

@Serializable
data class UsageDto(
    @SerialName("audio_bytes") val audioBytes: Long = 0,
    @SerialName("documents_bytes") val documentsBytes: Long = 0,
    @SerialName("study_data_bytes") val studyDataBytes: Long = 0,
    @SerialName("waiting_bytes") val waitingBytes: Long = 0,
    @SerialName("waiting_jobs") val waitingJobs: Int = 0,
    @SerialName("backups_bytes") val backupsBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("limit_bytes") val limitBytes: Long = 0,
    @SerialName("disk_total_bytes") val diskTotalBytes: Long = 0,
    @SerialName("disk_free_bytes") val diskFreeBytes: Long = 0,
)

@Serializable
data class CleanupBody(@SerialName("audio_older_than_days") val days: Int = 60)

@Serializable
data class CleanupResult(
    @SerialName("deleted_files") val deletedFiles: Int = 0,
    @SerialName("freed_bytes") val freedBytes: Long = 0,
)

@Serializable
data class BackupDto(val name: String = "", val bytes: Long = 0, @SerialName("created_at") val createdAt: String = "")

@Serializable
data class BackupsDto(val keep: Int = 7, val backups: List<BackupDto> = emptyList())

@Serializable
data class BackupMade(val name: String = "", val bytes: Long = 0)

@Serializable
data class ProviderUse(val used: Int = 0, val limit: Int = 0)

@Serializable
data class AiUsageDto(
    val level: Int = 0,
    val fraction: Double = 0.0,
    val providers: Map<String, ProviderUse> = emptyMap(),
    @SerialName("resets_at") val resetsAt: String = "",
)

/** Server calls for Settings, Storage and AI usage (spec 6.8). */
interface StorageApi {
    @GET("storage/usage")
    suspend fun usage(): UsageDto

    @POST("storage/cleanup")
    suspend fun cleanup(@Body body: CleanupBody): CleanupResult

    @GET("storage/backups")
    suspend fun backups(): BackupsDto

    @POST("storage/backups/run")
    suspend fun runBackup(): BackupMade

    /** The zip of everything the owner made. Streamed to a file, never held in memory. */
    @Streaming
    @GET("storage/export")
    suspend fun export(): ResponseBody

    @GET("usage")
    suspend fun aiUsage(): AiUsageDto
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun storageApi(@Named("authed") client: OkHttpClient, json: Json): StorageApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(StorageApi::class.java)
}

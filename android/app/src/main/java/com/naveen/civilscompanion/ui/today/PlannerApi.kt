package com.naveen.civilscompanion.ui.today

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
import retrofit2.http.POST

@Serializable
data class RegenerateBody(val days: Int = 7)

@Serializable
data class RegenerateResult(
    val planned: Int = 0,
    @SerialName("missed_days") val missedDays: Int = 0,
)

@Serializable
data class GenerateCardsBody(@SerialName("topic_id") val topicId: String? = null)

@Serializable
data class GenerateCardsResult(val created: Int = 0)

/** The planner's own server calls. Plans and cards themselves arrive through sync; these only ask the server to make them now. */
interface PlannerApi {
    /** Plans the next `days` days again (keeps blocks already ticked done). Answer: {"planned": n, "missed_days": n, "plans": [...]} */
    @POST("planner/regenerate")
    suspend fun regenerate(@Body body: RegenerateBody): RegenerateResult

    /** Makes flashcards from the owner's notes and highlights that have none yet. Answer: {"created": n} */
    @POST("revision/generate")
    suspend fun generateCards(@Body body: GenerateCardsBody): GenerateCardsResult
}

@Module
@InstallIn(SingletonComponent::class)
object PlannerModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun plannerApi(@Named("authed") client: OkHttpClient, json: Json): PlannerApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlannerApi::class.java)
}

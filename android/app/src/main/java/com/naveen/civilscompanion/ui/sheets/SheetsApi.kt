package com.naveen.civilscompanion.ui.sheets

import android.content.Context
import com.naveen.civilscompanion.util.CaptureFiles
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/** The server's PDF downloads for revision sheets and the weekly report (login token is added by the "authed" client). */
interface ReportsApi {
    @Streaming
    @GET("reports/sheets/{id}/pdf")
    suspend fun sheetPdf(@Path("id") id: String): ResponseBody

    @Streaming
    @GET("reports/weekly/{id}/pdf")
    suspend fun weeklyPdf(@Path("id") id: String): ResponseBody
}

@Module
@InstallIn(SingletonComponent::class)
object ReportsModule {
    private const val PLACEHOLDER_BASE = "https://placeholder.invalid/"

    @Provides @Singleton
    fun reportsApi(@Named("authed") client: OkHttpClient, json: Json): ReportsApi =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ReportsApi::class.java)
}

/** Downloads a PDF into the app's private "exports" folder (shared through the FileProvider). Returns null when it failed. */
@Singleton
class PdfDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ReportsApi,
) {
    suspend fun sheet(sheetId: String): File? = fetch("sheet_$sheetId.pdf") { api.sheetPdf(sheetId) }

    suspend fun weekly(reportId: String): File? = fetch("weekly_$reportId.pdf") { api.weeklyPdf(reportId) }

    private suspend fun fetch(name: String, call: suspend () -> ResponseBody): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, name)
            call().use { body -> body.byteStream().use { input -> file.outputStream().use { out -> input.copyTo(out) } } }
            file
        } catch (e: IOException) {
            null
        } catch (e: HttpException) {
            null
        }
    }

    fun uriOf(file: File) = CaptureFiles.uriFor(context, file)
}

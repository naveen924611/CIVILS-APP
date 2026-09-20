package com.naveen.civilscompanion.data.repo

import android.content.Context
import com.naveen.civilscompanion.data.auth.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Audio files saved on the tablet so briefs play with no internet. */
@Singleton
class AudioStore @Inject constructor(
    @ApplicationContext context: Context,
    @Named("authed") private val client: OkHttpClient,
    private val tokens: TokenStore,
) {
    private val dir = File(context.filesDir, "audio").apply { mkdirs() }
    private val _downloaded = MutableStateFlow(scan())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    fun file(itemId: String) = File(dir, "$itemId.mp3")

    fun has(itemId: String) = file(itemId).let { it.isFile && it.length() > 0 }

    /** Downloads one file. Returns true when it is on the tablet afterwards. */
    suspend fun download(itemId: String, audioPath: String): Boolean = withContext(Dispatchers.IO) {
        if (has(itemId)) return@withContext true
        val tmp = File(dir, "$itemId.part")
        try {
            val request = Request.Builder().url(tokens.serverUrl + audioPath).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful) return@withContext false
                tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
            val ok = tmp.length() > 0 && tmp.renameTo(file(itemId))
            if (ok) _downloaded.value = scan()
            ok
        } catch (e: java.io.IOException) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Removes audio older than [maxAgeDays] to keep storage small. */
    fun deleteOlderThan(maxAgeDays: Int) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        _downloaded.value = scan()
    }

    private fun scan(): Set<String> =
        dir.listFiles()?.filter { it.name.endsWith(".mp3") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".mp3") }?.toSet() ?: emptySet()
}

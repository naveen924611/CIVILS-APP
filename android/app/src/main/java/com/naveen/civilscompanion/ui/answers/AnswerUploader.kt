package com.naveen.civilscompanion.ui.answers

import android.content.Context
import android.net.Uri
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException

/**
 * Keeps the photos and typed text of written answers on the tablet and sends them to the server when the network
 * allows (spec 6.21: "queued if offline"). Steps for photos: the owner presses send -> a marker file is written ->
 * the photos are uploaded (the server needs the answer row first, so a 404 just means "try again soon") -> an
 * `answer_eval` job is queued -> the photos on the tablet are deleted. The retry loop runs while the app is open.
 */
@Singleton
class AnswerUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AnswersApi,
    private val jobs: JobRepository,
) {
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var quickRetry = false

    private val root: File get() = File(context.filesDir, "answer_photos")

    private fun folder(answerId: String): File = File(root, answerId.filter { it.isLetterOrDigit() || it == '-' }).also { it.mkdirs() }

    // ------------------------------------------------------------------ files on the tablet

    fun photos(answerId: String): List<File> =
        folder(answerId).listFiles { f -> f.isFile && f.name.startsWith("page_") }?.sortedBy { it.name }.orEmpty()

    /** Copies a photo taken with the camera into the answer's folder. Returns the saved file. */
    suspend fun addPhoto(answerId: String, source: File): File? = withContext(Dispatchers.IO) {
        try {
            val target = File(folder(answerId), "page_%013d.jpg".format(System.currentTimeMillis()))
            source.copyTo(target, overwrite = true)
            source.delete()
            target
        } catch (e: IOException) {
            null
        }
    }

    /** Copies a picture chosen from the gallery. */
    suspend fun addPhoto(answerId: String, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val target = File(folder(answerId), "page_%013d.jpg".format(System.currentTimeMillis()))
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source -> target.outputStream().use { out -> source.copyTo(out) } }
            target
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun removePhoto(file: File) {
        file.delete()
    }

    fun typedText(answerId: String): String = try {
        File(folder(answerId), TYPED).takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: IOException) {
        ""
    }

    fun saveTypedText(answerId: String, text: String) {
        try {
            File(folder(answerId), TYPED).writeText(text)
        } catch (e: IOException) {
            // the text stays on screen; it is saved again with the next change
        }
    }

    /** True while photos have been sent for feedback but are not yet on the server. */
    fun isUploadPending(answerId: String): Boolean {
        val dir = folder(answerId)
        return File(dir, SUBMITTED).exists() && !File(dir, JOB).exists()
    }

    // ------------------------------------------------------------------ sending

    /** The owner pressed "send" for photos: remember it and try at once. */
    suspend fun submitPhotos(answerId: String) {
        withContext(Dispatchers.IO) { File(folder(answerId), SUBMITTED).writeText("1") }
        kick()
    }

    /** The owner pressed "send" for a typed answer: the text travels inside the job. */
    suspend fun submitTyped(answerId: String, text: String) {
        jobs.enqueue("answer_eval", jobPayload("answer_id" to answerId, "text" to text))
    }

    /** Asks the server to check an answer again (its photos are already there). */
    suspend fun requestAgain(answerId: String) {
        jobs.enqueue("answer_eval", jobPayload("answer_id" to answerId))
    }

    fun kick() {
        quickRetry = true
        wake.trySend(Unit)
    }

    /** Runs for as long as the app is open: retries waiting photo uploads. */
    suspend fun runLoop() {
        while (true) {
            retryPending()
            val wait = if (quickRetry) 10_000L else 45_000L
            quickRetry = false
            withTimeoutOrNull(wait) { wake.receive() }
            delay(300)
        }
    }

    suspend fun retryPending() {
        val ids = withContext(Dispatchers.IO) {
            root.listFiles { f -> f.isDirectory && File(f, SUBMITTED).exists() && !File(f, JOB).exists() }?.map { it.name }.orEmpty()
        }
        for (id in ids) upload(id)
    }

    private suspend fun upload(answerId: String): Unit = lock.withLock {
        val dir = folder(answerId)
        if (File(dir, JOB).exists()) return@withLock
        val files = photos(answerId)
        if (files.isEmpty()) {
            File(dir, SUBMITTED).delete() // nothing to send
            return@withLock
        }
        try {
            val parts = files.map { f ->
                MultipartBody.Part.createFormData("files", f.name, f.asRequestBody("image/jpeg".toMediaType()))
            }
            withContext(Dispatchers.IO) { api.upload(answerId, true, parts).close() }
        } catch (e: IOException) {
            quickRetry = false
            return@withLock // no internet: try again later
        } catch (e: HttpException) {
            quickRetry = e.code() == 404 // the answer has not reached the server yet
            return@withLock
        } catch (e: SerializationException) {
            return@withLock
        }
        val jobId = jobs.enqueue("answer_eval", jobPayload("answer_id" to answerId))
        withContext(Dispatchers.IO) {
            File(dir, JOB).writeText(jobId)
            files.forEach { it.delete() }
        }
    }

    private companion object {
        const val TYPED = "typed.txt"
        const val SUBMITTED = ".submitted"
        const val JOB = ".job"
    }
}

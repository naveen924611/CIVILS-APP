package com.naveen.civilscompanion.ui.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.HttpException

/** What happened to a send: [message] is short and plain (shown to the owner). */
data class SendResult(
    val ok: Boolean,
    val message: String,
    val documentId: String? = null,
    val page: Int = 0,
    /** true when the failure was only "no internet", so the item is kept and sent later. */
    val offline: Boolean = false,
)

/** Streams a file the owner picked (content:// address) into an upload without loading it all into memory. */
private class UriBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val type: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")
        input.use { it.copyTo(sink.outputStream()) }
    }
}

/** Uploads, downloads and the queue of photos waiting to be sent. Screens and workers share this one object. */
@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: LibraryApi,
    private val store: RecordStore,
    private val jobs: JobRepository,
) {
    private val flushLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ uploads from the file picker

    suspend fun uploadPdf(uri: Uri): SendResult = guarded {
        val resolver = context.contentResolver
        val name = displayName(uri, "document.pdf")
        val part = MultipartBody.Part.createFormData("files", name, UriBody(resolver, uri, "application/pdf".toMediaType()))
        finishUpload(api.upload(listOf(part), null))
    }

    suspend fun uploadImages(uris: List<Uri>, title: String? = null): SendResult = guarded {
        val resolver = context.contentResolver
        val parts = uris.mapIndexed { i, uri ->
            val type = (resolver.getType(uri) ?: "image/jpeg").toMediaType()
            MultipartBody.Part.createFormData("files", displayName(uri, "photo$i.jpg"), UriBody(resolver, uri, type))
        }
        val titleBody = title?.takeIf { it.isNotBlank() }?.toRequestBody("text/plain; charset=utf-8".toMediaType())
        finishUpload(api.upload(parts, titleBody))
    }

    private fun finishUpload(answer: JsonObject): SendResult {
        SyncScheduler.syncNow(context)
        val first = (answer["documents"] as? JsonArray)?.firstOrNull()?.jsonObject
        val id = first?.get("id")?.jsonPrimitive?.content
        val problems = (answer["problems"] as? JsonArray)?.size ?: 0
        val note = if (problems > 0) " ($problems file(s) could not be added.)" else ""
        return SendResult(true, "Sent. The server is reading it now.$note", documentId = id)
    }

    suspend fun retry(documentId: String): SendResult = guarded {
        api.retry(documentId)
        SyncScheduler.syncNow(context)
        SendResult(true, "Trying again.", documentId)
    }

    suspend fun downloadMaterial(key: String): SendResult = guarded {
        val answer = api.downloadMaterial(key)
        SyncScheduler.syncNow(context)
        SendResult(true, "Downloading. It will appear in My uploads.", answer["id"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------ camera photos (offline first)

    fun observePending(): Flow<List<PendingScan>> =
        store.observe(LocalTables.PendingScans, RecordQuery(order = Order.OldestFirst))

    /** Keeps a copy of the photo on this tablet and remembers it until it has been sent. Returns the queue id. */
    suspend fun queueScan(photo: File, text: String, batch: String, documentId: String?, telugu: Boolean): String =
        withContext(Dispatchers.IO) {
            val id = TimeUtil.newId()
            val dir = File(context.filesDir, "pending_scans").apply { mkdirs() }
            val keep = File(dir, "$id.jpg")
            photo.copyTo(keep, overwrite = true)
            store.save(
                LocalTables.PendingScans,
                PendingScan(
                    id = id, filePath = keep.absolutePath, text = text, documentId = documentId,
                    batch = batch, telugu = telugu, createdAt = TimeUtil.nowIso(),
                ),
            )
            id
        }

    suspend fun pendingCount(): Int = store.count(LocalTables.PendingScans, RecordQuery(k1 = "waiting"))

    /** Sends every waiting photo, oldest first. Stops at the first "no internet". Returns the last result. */
    suspend fun flushScans(): SendResult? = flushLock.withLock {
        var last: SendResult? = null
        val rows = store.list(LocalTables.PendingScans, RecordQuery(k1 = "waiting", order = Order.OldestFirst))
        for (queued in rows) {
            // an earlier page of the same batch may have created the document since this row was read
            val row = store.get(LocalTables.PendingScans, queued.id) ?: continue
            val file = File(row.filePath)
            if (!file.isFile) {
                store.delete(LocalTables.PendingScans, row.id)
                continue
            }
            val result = sendScan(row, file)
            last = result
            when {
                result.ok -> {
                    store.delete(LocalTables.PendingScans, row.id)
                    file.delete()
                    val docId = result.documentId
                    if (docId != null && row.batch.isNotEmpty()) shareDocument(row.batch, docId)
                }
                result.offline -> break
                else -> store.update(LocalTables.PendingScans, row.id) { it.copy(state = "failed", message = result.message) }
            }
        }
        last
    }

    private suspend fun shareDocument(batch: String, documentId: String) {
        val others = store.list(LocalTables.PendingScans, RecordQuery(k1 = "waiting"))
        for (other in others) {
            if (other.batch == batch && other.documentId == null) {
                store.update(LocalTables.PendingScans, other.id) { it.copy(documentId = documentId) }
            }
        }
    }

    private suspend fun sendScan(row: PendingScan, file: File): SendResult = guarded {
        val image = MultipartBody.Part.createFormData("image", "page.jpg", file.asRequestBodyJpeg())
        val plain = "text/plain; charset=utf-8".toMediaType()
        // For a Telugu page the on-device reader cannot help, so the text is left empty and the server reads it with the AI.
        val text = if (row.telugu) "" else row.text
        val answer = api.scan(
            image,
            row.documentId?.toRequestBody(plain),
            text.takeIf { it.isNotBlank() }?.toRequestBody(plain),
            (if (row.telugu) "te" else "en").toRequestBody(plain),
        )
        SyncScheduler.syncNow(context)
        SendResult(
            true, "Saved to your library.",
            documentId = answer["document_id"]?.jsonPrimitive?.content,
            page = answer["page"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun File.asRequestBodyJpeg(): RequestBody = readBytes().toRequestBody("image/jpeg".toMediaType())

    /** Asks the server's AI to read one page again (used from the Reader). */
    suspend fun queuePageOcr(documentId: String, page: Int, telugu: Boolean): String =
        jobs.enqueue(
            "ocr_page",
            jobPayload("document_id" to documentId, "page" to page, "only_this_page" to true, "force" to true, "language" to if (telugu) "te" else "en"),
        )

    // ------------------------------------------------------------------ files for the Reader

    /** The PDF on this tablet (downloaded once, then kept). Null when it cannot be fetched. */
    suspend fun pdfFile(documentId: String): File? = withContext(Dispatchers.IO) {
        val target = File(File(context.filesDir, "library").apply { mkdirs() }, "$documentId.pdf")
        if (target.isFile && target.length() > 0) return@withContext target
        try {
            api.file(documentId).use { body -> body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
            target
        } catch (e: IOException) {
            target.delete()
            null
        } catch (e: HttpException) {
            target.delete()
            null
        }
    }

    fun cachedPdf(documentId: String): File? =
        File(File(context.filesDir, "library"), "$documentId.pdf").takeIf { it.isFile && it.length() > 0 }

    /** The picture of one scanned page (kept in the cache folder). Null when there is none or no internet. */
    suspend fun pageImageFile(documentId: String, page: Int): File? = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "library_pages").apply { mkdirs() }, "${documentId}_$page.jpg")
        if (target.isFile && target.length() > 0) return@withContext target
        try {
            api.pageImage(documentId, page).use { body -> body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
            target
        } catch (e: IOException) {
            target.delete()
            null
        } catch (e: HttpException) {
            target.delete()
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun guarded(block: suspend () -> SendResult): SendResult = try {
        block()
    } catch (e: HttpException) {
        SendResult(false, serverMessage(e))
    } catch (e: IOException) {
        SendResult(false, "No internet right now. Please try again when you are online.", offline = true)
    } catch (e: SecurityException) {
        SendResult(false, "The app could not open that file. Please choose it again.")
    }

    private fun serverMessage(e: HttpException): String {
        val body = try {
            e.response()?.errorBody()?.string().orEmpty()
        } catch (io: IOException) {
            ""
        }
        val detail = try {
            json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content
        } catch (x: IllegalArgumentException) {
            null
        }
        return detail?.takeIf { it.isNotBlank() } ?: "The server could not do that (error ${e.code()})."
    }

    private fun displayName(uri: Uri, fallback: String): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) {
                    val name = c.getString(col)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: SecurityException) {
            return fallback
        }
        return fallback
    }
}

package com.naveen.civilscompanion.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Highlight
import com.naveen.civilscompanion.data.model.Job as QueuedJob
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.reader.Fact
import com.naveen.civilscompanion.reader.FactSpotter
import com.naveen.civilscompanion.ui.ask.AskDraft
import com.naveen.civilscompanion.ui.library.LibraryRepository
import com.naveen.civilscompanion.ui.library.ScanUploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A spotted fact. [known] = it already appears in the owner's notes. */
data class FactRow(val fact: Fact, val known: Boolean)

data class CaptureUiState(
    val photoPath: String? = null,
    val preview: Bitmap? = null,
    val text: String = "",
    /** Changes whenever the text was replaced from outside the text box (a new photo was read). */
    val version: Int = 0,
    val reading: Boolean = false,
    val telugu: Boolean = false,
    val documentId: String? = null,
    val docTitle: String? = null,
    val pagesSaved: Int = 0,
    val facts: List<FactRow> = emptyList(),
    val cardsMade: Set<String> = emptySet(),
    val waitingPhotos: Int = 0,
    val saving: Boolean = false,
    val message: String? = null,
    /** queued | running | done | failed, or null when no merge was asked for. */
    val mergeStatus: String? = null,
    val mergeSummary: String = "",
)

private data class Local(
    val photoPath: String? = null,
    val preview: Bitmap? = null,
    val text: String = "",
    val version: Int = 0,
    val reading: Boolean = false,
    val telugu: Boolean = false,
    val documentId: String? = null,
    val docTitle: String? = null,
    val pagesSaved: Int = 0,
    val facts: List<FactRow> = emptyList(),
    val cardsMade: Set<String> = emptySet(),
    val saving: Boolean = false,
    val message: String? = null,
    val mergeJobId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RecordStore,
    private val repo: LibraryRepository,
    private val jobs: JobRepository,
    private val askDraft: AskDraft,
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private val batch = TimeUtil.newId()
    private var started = false
    private var ocrJob: kotlinx.coroutines.Job? = null
    private var factsJob: kotlinx.coroutines.Job? = null

    private val mergeJob: Flow<QueuedJob?> = local.map { it.mergeJobId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf<QueuedJob?>(null) else jobs.observe(id)
    }
    private val waiting: Flow<Int> = repo.observePending().map { list -> list.count { it.state == "waiting" } }

    val state: StateFlow<CaptureUiState> = combine(local, mergeJob, waiting) { l, job, wait ->
        CaptureUiState(
            photoPath = l.photoPath, preview = l.preview, text = l.text, version = l.version, reading = l.reading,
            telugu = l.telugu, documentId = l.documentId, docTitle = l.docTitle, pagesSaved = l.pagesSaved,
            facts = l.facts, cardsMade = l.cardsMade, waitingPhotos = wait, saving = l.saving, message = l.message,
            mergeStatus = job?.status,
            mergeSummary = (job?.result?.get("summary") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureUiState())

    /** Called when the screen opens. Adds to the scan the Reader asked for (if any) and sends photos taken earlier. */
    fun start() {
        if (started) return
        started = true
        val target = CaptureTarget.take()
        viewModelScope.launch {
            if (target != null) {
                val doc = store.get(Tables.Documents, target)
                if (doc != null) local.update { it.copy(documentId = doc.id, docTitle = doc.title) }
            }
            repo.flushScans()
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun tell(text: String) = local.update { it.copy(message = text) }

    fun setTelugu(on: Boolean) = local.update { it.copy(telugu = on) }

    // ------------------------------------------------------------------ a new photo

    /** A photo file is ready (from the camera or a copy of a picked picture). */
    fun onPhoto(file: File) {
        ocrJob?.cancel()
        factsJob?.cancel()
        local.update {
            it.copy(
                photoPath = file.absolutePath, preview = null, text = "", version = it.version + 1, facts = emptyList(),
                cardsMade = emptySet(), reading = false, message = null, mergeJobId = null,
            )
        }
        ocrJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { PhotoBitmaps.load(file, 2000) }
            if (bitmap == null) {
                tell("The photo could not be opened. Please take it again.")
                return@launch
            }
            local.update { it.copy(preview = bitmap) }
            if (local.value.telugu) {
                tell("Telugu page: save it, and the server's AI reads it when you are online.")
                return@launch
            }
            local.update { it.copy(reading = true) }
            val found = OnDeviceOcr.read(bitmap)
            val text = found.orEmpty()
            local.update {
                it.copy(
                    reading = false, text = text, version = it.version + 1,
                    message = when {
                        found == null -> "The text reader on this tablet could not start. Save the photo and the server will read it."
                        text.isBlank() -> "No text was found. Try again in better light, or save it for the server to read."
                        else -> null
                    },
                )
            }
            spot(text)
        }
    }

    /** A picture chosen from the gallery: copied to a working file first. */
    fun onPicked(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val (target, _) = com.naveen.civilscompanion.util.CaptureFiles.newPhoto(context, "picked")
                    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("no input")
                    target
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
            if (file == null) tell("That picture could not be opened.") else onPhoto(file)
        }
    }

    fun setText(text: String) {
        if (text == local.value.text) return
        local.update { it.copy(text = text) }
        spot(text)
    }

    private fun spot(text: String) {
        factsJob?.cancel()
        factsJob = viewModelScope.launch {
            delay(300)
            val rows = FactSpotter.spot(text).map { f ->
                FactRow(f, known = store.list(Tables.Notes, RecordQuery(contains = f.text, limit = 1)).isNotEmpty())
            }
            local.update { it.copy(facts = rows) }
        }
    }

    // ------------------------------------------------------------------ what to do with the text

    private fun textOrSelection(selection: String): String = selection.ifBlank { local.value.text }.trim()

    /** kind: point | must | card. [selection] is the part the owner selected ("" = the whole text). */
    fun saveHighlight(kind: String, selection: String) {
        val text = textOrSelection(selection)
        if (text.isEmpty()) {
            tell("There is no text yet.")
            return
        }
        saveHighlightText(kind, text)
        tell(
            when (kind) {
                "must" -> "Saved as \"must remember\"."
                "card" -> "Added to revision. A flashcard is made from it."
                else -> "Saved as a point."
            },
        )
    }

    private fun saveHighlightText(kind: String, text: String) {
        val s = local.value
        viewModelScope.launch {
            store.save(
                Tables.Highlights,
                Highlight(id = TimeUtil.newId(), documentId = s.documentId, page = s.pagesSaved + 1, text = text, kind = kind),
            )
        }
    }

    fun makeCard(row: FactRow) {
        if (row.fact.text in local.value.cardsMade) return
        saveHighlightText("card", row.fact.sentence)
        local.update { it.copy(cardsMade = it.cardsMade + row.fact.text, message = "Flashcard added for ${row.fact.text}.") }
    }

    /** "Turn into flashcards" for every spotted fact that is not already in the notes. */
    fun makeAllCards() {
        val s = local.value
        val todo = s.facts.filter { !it.known && it.fact.text !in s.cardsMade }.distinctBy { it.fact.sentence }
        if (todo.isEmpty()) {
            tell("Nothing new to turn into flashcards.")
            return
        }
        todo.forEach { saveHighlightText("card", it.fact.sentence) }
        local.update { it.copy(cardsMade = it.cardsMade + s.facts.map { f -> f.fact.text }, message = "${todo.size} flashcard${if (todo.size == 1) "" else "s"} added.") }
    }

    fun prepareAsk(selection: String): Boolean {
        val text = textOrSelection(selection)
        if (text.isEmpty()) {
            tell("There is no text yet.")
            return false
        }
        askDraft.set("Explain this simply: ${text.take(600)}", null, local.value.docTitle ?: "Scanned page")
        return true
    }

    /** Queues the AI merge of this text into the notes. It works offline and runs when the tablet is online. */
    fun queueMerge(selection: String) {
        val s = local.value
        val text = textOrSelection(selection).take(6000)
        if (text.isEmpty()) {
            tell("There is no text yet.")
            return
        }
        val pairs = mutableListOf<Pair<String, Any?>>("text" to text, "title" to (s.docTitle ?: "Scanned page"), "mode" to "merge")
        s.documentId?.let { id ->
            pairs.add("source" to JsonObject(mapOf("document_id" to JsonPrimitive(id), "page" to JsonPrimitive(s.pagesSaved.coerceAtLeast(1)))))
        }
        viewModelScope.launch {
            val id = jobs.enqueue("note_merge", jobPayload(*pairs.toTypedArray()))
            local.update { it.copy(mergeJobId = id, message = "Queued. Your notes are updated when you are online.") }
        }
    }

    // ------------------------------------------------------------------ saving the page

    /** Keeps the photo on this tablet and sends it now, or later when there is internet. */
    fun savePage() {
        val s = local.value
        val path = s.photoPath ?: return
        if (s.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            repo.queueScan(File(path), s.text, batch, s.documentId, s.telugu)
            ScanUploadWorker.enqueue(context)
            val result = repo.flushScans()
            val docId = result?.documentId
            val note = if (result == null) {
                "Saved on this tablet. It is sent when you are online."
            } else if (result.offline) {
                "Saved on this tablet. It is sent when you are online."
            } else if (result.ok) {
                "Page saved to your library. Take the next page, or finish."
            } else {
                result.message
            }
            local.update {
                it.copy(
                    saving = false,
                    photoPath = null, preview = null, text = "", version = it.version + 1, facts = emptyList(), cardsMade = emptySet(),
                    pagesSaved = it.pagesSaved + 1,
                    documentId = docId ?: it.documentId,
                    message = note,
                )
            }
        }
    }

    fun sendWaiting() {
        viewModelScope.launch {
            val r = repo.flushScans()
            tell(r?.message ?: "Nothing is waiting.")
        }
    }
}

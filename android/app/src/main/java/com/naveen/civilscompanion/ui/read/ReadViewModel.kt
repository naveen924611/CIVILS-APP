package com.naveen.civilscompanion.ui.read

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.DocPage
import com.naveen.civilscompanion.data.model.Highlight
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.reader.Paragraph
import com.naveen.civilscompanion.reader.ReadingPosition
import com.naveen.civilscompanion.reader.Sentence
import com.naveen.civilscompanion.reader.SentenceSplitter
import com.naveen.civilscompanion.reader.askAboutPageText
import com.naveen.civilscompanion.reader.looksTelugu
import com.naveen.civilscompanion.reader.paragraphsOf
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.ui.ask.AskDraft
import com.naveen.civilscompanion.ui.capture.OnDeviceOcr
import com.naveen.civilscompanion.ui.capture.PhotoBitmaps
import com.naveen.civilscompanion.ui.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ReadUiState(
    val loaded: Boolean = false,
    val doc: LibDocument? = null,
    val pages: List<DocPage> = emptyList(),
    val index: Int = 0,
    val pageNumber: Int = 1,
    val text: String = "",
    val sentences: List<Sentence> = emptyList(),
    val paragraphs: List<Paragraph> = emptyList(),
    /** The sentence being read, or the one the owner tapped (-1 = none). */
    val current: Int = -1,
    val speaking: Boolean = false,
    val highlighted: Set<Int> = emptySet(),
    val telugu: Boolean = false,
    val speed: Float = 1f,
    val sleepMinutes: Int = 0,
    val showOriginal: Boolean = false,
    val original: Bitmap? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

private data class Local(
    val pageNumber: Int = 1,
    val current: Int = -1,
    val speaking: Boolean = false,
    val speed: Float = 1f,
    val sleepMinutes: Int = 0,
    val showOriginal: Boolean = false,
    val original: Bitmap? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

val READ_SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f)
val SLEEP_CHOICES = listOf(0, 10, 20, 30, 45)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadViewModel @Inject constructor(
    private val store: RecordStore,
    private val tts: TtsSpeaker,
    private val jobs: JobRepository,
    private val repo: LibraryRepository,
    private val askDraft: AskDraft,
) : ViewModel() {

    private val docId = MutableStateFlow("")
    private val local = MutableStateFlow(Local())
    private var sleepJob: Job? = null
    private var originalJob: Job? = null

    private val docFlow: Flow<LibDocument?> = docId.flatMapLatest { id ->
        if (id.isEmpty()) flowOf<LibDocument?>(null) else store.observeOne(Tables.Documents, id)
    }
    private val pagesFlow: Flow<List<DocPage>> = docId.flatMapLatest { id ->
        if (id.isEmpty()) flowOf(emptyList<DocPage>()) else store.observe(Tables.DocPages, RecordQuery(k1 = id, order = Order.NumberAsc, limit = 5000))
    }
    private val highlightsFlow: Flow<List<Highlight>> = docId.flatMapLatest { id ->
        if (id.isEmpty()) flowOf(emptyList<Highlight>()) else store.observe(Tables.Highlights, RecordQuery(k1 = id))
    }

    val state: StateFlow<ReadUiState> = combine(docFlow, pagesFlow, highlightsFlow, local) { doc, allPages, highlights, l ->
        val pages = allPages.filter { !it.deleted }.sortedBy { it.page }
        var index = pages.indexOfFirst { it.page >= l.pageNumber }
        if (index < 0) index = pages.lastIndex.coerceAtLeast(0)
        val page = pages.getOrNull(index)
        val text = page?.text.orEmpty()
        val sentences = SentenceSplitter.split(text)
        val pageNumber = page?.page ?: l.pageNumber
        val marked = highlights.filter { !it.deleted && it.page == pageNumber }.map { it.text }.toSet()
        ReadUiState(
            loaded = doc != null && !doc.deleted,
            doc = doc,
            pages = pages,
            index = index,
            pageNumber = pageNumber,
            text = text,
            sentences = sentences,
            paragraphs = paragraphsOf(text),
            current = if (l.current in sentences.indices) l.current else -1,
            speaking = l.speaking,
            highlighted = sentences.indices.filter { sentences[it].text in marked }.toSet(),
            telugu = looksTelugu(text),
            speed = l.speed,
            sleepMinutes = l.sleepMinutes,
            showOriginal = l.showOriginal,
            original = l.original,
            busy = l.busy,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadUiState())

    /** Opens a document and goes back to where the owner stopped last time. */
    fun load(id: String) {
        if (docId.value == id) return
        docId.value = id
        local.value = Local()
        viewModelScope.launch {
            val doc = store.get(Tables.Documents, id)
            val pos = ReadingPosition.from(doc?.readingPosition)
            local.update { it.copy(pageNumber = pos.page, current = if (pos.sentence > 0) pos.sentence else -1) }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun say(text: String) = local.update { it.copy(message = text, busy = false) }

    // ------------------------------------------------------------------ pages

    fun goToIndex(target: Int) {
        val s = state.value
        val row = s.pages.getOrNull(target) ?: return
        val keepReading = local.value.speaking
        local.update { it.copy(pageNumber = row.page, current = -1) }
        savePosition(row.page, 0)
        if (local.value.showOriginal) loadOriginal(row.page)
        if (keepReading) {
            val sentences = SentenceSplitter.split(row.text)
            if (sentences.isEmpty()) stopSpeaking() else {
                local.update { it.copy(current = 0) }
                speakFrom(sentences, 0, looksTelugu(row.text))
            }
        }
    }

    fun nextPage() {
        goToIndex((state.value.index + 1).coerceAtMost(state.value.pages.lastIndex))
    }

    fun previousPage() {
        goToIndex((state.value.index - 1).coerceAtLeast(0))
    }

    fun openPage(index: Int) = goToIndex(index)

    // ------------------------------------------------------------------ reading aloud

    fun toggleReading() {
        if (local.value.speaking) pause() else play()
    }

    fun play(from: Int = -1) {
        val s = state.value
        if (s.sentences.isEmpty()) {
            say("This page has no text yet. Use \"Read this page\" below.")
            return
        }
        val start = (if (from >= 0) from else s.current).coerceIn(0, s.sentences.lastIndex)
        local.update { it.copy(speaking = true, current = start) }
        speakFrom(s.sentences, start, s.telugu)
    }

    private fun speakFrom(sentences: List<Sentence>, start: Int, telugu: Boolean) {
        tts.speakSentences(
            sentences.map { it.text }, start, if (telugu) "te-IN" else "en-IN", local.value.speed,
            onSentence = { i ->
                local.update { it.copy(current = i) }
                if (i % 8 == 0) savePosition(local.value.pageNumber, i)
            },
            onDone = { pageFinished() },
        )
    }

    private fun pageFinished() {
        if (!local.value.speaking) return
        val s = state.value
        val next = (s.index + 1 until s.pages.size).firstOrNull { s.pages[it].text.isNotBlank() }
        if (next == null) {
            local.update { it.copy(speaking = false, current = -1) }
            say("That was the last page with text.")
        } else {
            goToIndex(next)
        }
    }

    fun pause() {
        stopSpeaking()
        savePosition(local.value.pageNumber, state.value.current.coerceAtLeast(0))
    }

    private fun stopSpeaking() {
        if (local.value.speaking) tts.stop()
        local.update { it.copy(speaking = false) }
    }

    /** Tap a sentence: reading starts from it. */
    fun tapSentence(index: Int) {
        if (index < 0) return
        play(index)
    }

    fun stepSentence(delta: Int) {
        val s = state.value
        if (s.sentences.isEmpty()) return
        val target = ((if (s.current < 0) 0 else s.current) + delta).coerceIn(0, s.sentences.lastIndex)
        if (local.value.speaking) play(target) else local.update { it.copy(current = target) }
    }

    fun cycleSpeed() {
        val at = READ_SPEEDS.indexOfFirst { kotlin.math.abs(it - local.value.speed) < 0.01f }
        local.update { it.copy(speed = READ_SPEEDS[(at + 1) % READ_SPEEDS.size]) }
        if (local.value.speaking) play(state.value.current)
    }

    fun cycleSleep() {
        val at = SLEEP_CHOICES.indexOf(local.value.sleepMinutes)
        val minutes = SLEEP_CHOICES[(at + 1) % SLEEP_CHOICES.size]
        local.update { it.copy(sleepMinutes = minutes) }
        sleepJob?.cancel()
        if (minutes > 0) {
            sleepJob = viewModelScope.launch {
                delay(minutes * 60_000L)
                pause()
                local.update { it.copy(sleepMinutes = 0, message = "Sleep timer: reading stopped.") }
            }
        }
    }

    // ------------------------------------------------------------------ highlights, notes, revision

    private fun selectedText(): String? {
        val s = state.value
        return s.sentences.getOrNull(s.current)?.text
    }

    /** kind: point | must | card. */
    fun highlight(kind: String) {
        val s = state.value
        val doc = s.doc ?: return
        val text = selectedText()
        if (text == null) {
            say("Tap a sentence first, then choose what to do with it.")
            return
        }
        viewModelScope.launch {
            store.save(
                Tables.Highlights,
                Highlight(id = TimeUtil.newId(), documentId = doc.id, page = s.pageNumber, text = text, kind = kind, topicId = doc.topicId),
            )
            say(
                when (kind) {
                    "must" -> "Saved as \"must remember\"."
                    "card" -> "Added to revision. A flashcard is made from it."
                    else -> "Saved as a point."
                },
            )
        }
    }

    /** Queues an AI merge of this sentence (or the whole page) into the notes. Works offline. */
    fun makeNotes() {
        val s = state.value
        val doc = s.doc ?: return
        val text = (selectedText() ?: s.text).trim().take(6000)
        if (text.isEmpty()) {
            say("This page has no text to make notes from.")
            return
        }
        val source = JsonObject(mapOf("document_id" to JsonPrimitive(doc.id), "page" to JsonPrimitive(s.pageNumber)))
        val pairs = mutableListOf<Pair<String, Any?>>("text" to text, "source" to source, "title" to doc.title, "mode" to "merge")
        doc.topicId?.let { pairs.add("topic_id" to it) }
        viewModelScope.launch {
            jobs.enqueue("note_merge", jobPayload(*pairs.toTypedArray()))
            say("Sent to your notes. It is added when you are online.")
        }
    }

    /** Puts a question about this page (or the tapped sentence) into the Ask box. The screen then opens Ask. */
    fun prepareAsk(sentenceOnly: Boolean): Boolean {
        val s = state.value
        val doc = s.doc ?: return false
        val label = "${doc.title}, page ${s.pageNumber}"
        val sentence = selectedText()
        if (sentenceOnly && sentence != null) {
            askDraft.set("Explain this simply: $sentence", doc.topicId, label)
        } else {
            askDraft.set(askAboutPageText(s.text, s.pageNumber, doc.title), doc.topicId, label)
        }
        return true
    }

    // ------------------------------------------------------------------ original page and reading a page without text

    fun toggleOriginal() {
        val on = !local.value.showOriginal
        local.update { it.copy(showOriginal = on, original = null) }
        if (on) loadOriginal(state.value.pageNumber)
    }

    private fun loadOriginal(page: Int) {
        val doc = state.value.doc ?: return
        originalJob?.cancel()
        local.update { it.copy(busy = true, original = null) }
        originalJob = viewModelScope.launch {
            val bitmap = pageBitmap(doc, page)
            local.update {
                it.copy(
                    original = bitmap, busy = false,
                    message = if (bitmap == null) "The original page cannot be shown. It needs the internet the first time." else it.message,
                )
            }
        }
    }

    private suspend fun pageBitmap(doc: LibDocument, page: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (doc.type == "scan" || doc.type == "image") {
            repo.pageImageFile(doc.id, page)?.let { PhotoBitmaps.load(it, 1800) }
        } else {
            val file = repo.cachedPdf(doc.id) ?: repo.pdfFile(doc.id)
            if (file == null) null else PhotoBitmaps.renderPdfPage(file, page - 1)
        }
    }

    /** Reads a page that has no text with this tablet's own text reader (English). */
    fun readOnDevice() {
        val s = state.value
        val doc = s.doc ?: return
        val row = s.pages.getOrNull(s.index) ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = "Reading the page on this tablet...") }
        viewModelScope.launch {
            val bitmap = pageBitmap(doc, row.page)
            val found = if (bitmap == null) null else OnDeviceOcr.read(bitmap)
            val clean = found.orEmpty()
            when {
                bitmap == null -> say("The page picture is not on this tablet yet. Connect to the internet once and try again.")
                found == null -> say("The text reader on this tablet could not start. Try \"Read with AI\".")
                clean.isBlank() -> say("No text was found. For Telugu pages use \"Read with AI\".")
                else -> {
                    store.update(Tables.DocPages, row.id) { it.copy(text = clean, source = "device_ocr") }
                    say("Done. The page is ready to read aloud.")
                }
            }
        }
    }

    /** Asks the server's AI to read this page (needed for Telugu). It arrives with the next sync. */
    fun readWithAi() {
        val s = state.value
        val doc = s.doc ?: return
        viewModelScope.launch {
            repo.queuePageOcr(doc.id, s.pageNumber, telugu = doc.language != "en")
            say("Sent. The page will be readable soon after you are online.")
        }
    }

    // ------------------------------------------------------------------ leaving

    private fun savePosition(page: Int, sentence: Int) {
        val doc = state.value.doc ?: return
        val now = ReadingPosition(page, sentence)
        if (ReadingPosition.from(doc.readingPosition) == now) return
        viewModelScope.launch(NonCancellable) {
            store.update(Tables.Documents, doc.id) { it.copy(readingPosition = now.toJson()) }
        }
    }

    /** The screen is closing: stop reading and remember the place. */
    fun leave() {
        if (local.value.speaking) tts.stop()
        local.update { it.copy(speaking = false) }
        savePosition(local.value.pageNumber, state.value.current.coerceAtLeast(0))
    }

    override fun onCleared() {
        if (local.value.speaking) tts.stop()
        super.onCleared()
    }
}

package com.naveen.civilscompanion.ui.notes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.sync.SyncScheduler
import com.naveen.civilscompanion.ui.ask.AskDraft
import com.naveen.civilscompanion.ui.syllabus.TNode
import com.naveen.civilscompanion.ui.syllabus.TopicTree
import com.naveen.civilscompanion.ui.syllabus.apiCall
import com.naveen.civilscompanion.ui.syllabus.describeError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.serialization.json.JsonPrimitive

/** The left side: the topic tree (or search results) and which topic is open. */
data class NotesListState(
    val topics: List<Topic> = emptyList(),
    val tree: List<TNode> = emptyList(),
    val exam: String? = null,
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val selectedId: String? = null,
    val message: String? = null,
    val speaking: Boolean = false,
    val versions: VersionsUi? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val askDraft: AskDraft,
    private val tts: TtsSpeaker,
    private val notesApi: NotesApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val exam = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val versions = MutableStateFlow<VersionsUi?>(null)

    private val noteHits: Flow<List<Note>> = query.flatMapLatest { q ->
        if (q.trim().length < 2) flowOf(emptyList<Note>()) else store.observe(Tables.Notes, RecordQuery(contains = q.trim(), limit = 40))
    }

    private val base = combine(store.observe(Tables.Topics), exam, query, noteHits) { topics, examNow, q, notes ->
        val tree = TopicTree.build(topics, examNow)
        val byId = topics.associateBy { it.id }
        val words = q.trim()
        val titleHits = if (words.length >= 2) TopicTree.search(tree, words).map { SearchHit(it.topic, "") } else emptyList()
        val noteOnly = notes.mapNotNull { n ->
            val topic = byId[n.topicId] ?: return@mapNotNull null
            if (titleHits.any { it.topic.id == topic.id }) null else SearchHit(topic, NoteLogic.snippet(n.contentMd, words))
        }
        NotesListState(topics = topics, tree = tree, exam = examNow, query = q, hits = (titleHits + noteOnly).distinctBy { it.topic.id })
    }

    val list: StateFlow<NotesListState> = combine(base, selected, message, tts.speaking, versions) { b, sel, msg, speaking, v ->
        b.copy(selectedId = sel, message = msg, speaking = speaking, versions = v)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesListState())

    val detail: StateFlow<DetailState> = selected.flatMapLatest { id ->
        if (id == null) flowOf(DetailState()) else detailFlow(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    private fun detailFlow(id: String): Flow<DetailState> {
        val data = combine(
            store.observe(Tables.Notes, RecordQuery(k1 = id)),
            store.observe(Tables.Cards, RecordQuery(k1 = id)),
            store.observe(Tables.Mcqs, RecordQuery(k1 = id)),
        ) { notes, cards, mcqs -> Triple(notes, cards, mcqs) }
        val extra = combine(
            store.observeOne(Tables.Topics, id),
            store.observe(NoteTables.Doubts, RecordQuery(k1 = id, order = Order.NewestFirst)),
            jobs.observeRecent("note_merge", 30),
        ) { topic, doubts, recent -> Triple(topic, doubts, recent) }
        return combine(data, extra) { (notes, cards, mcqs), (topic, doubts, recent) ->
            DetailState(
                topic = topic,
                note = notes.firstOrNull(),
                cards = cards,
                mcqs = mcqs,
                doubts = doubts,
                job = latestJob(recent, id),
            )
        }
    }

    /** The newest note job for this topic that is still interesting: waiting, failed, or finished a few minutes ago. */
    private fun latestJob(recent: List<Job>, topicId: String): TopicJob? {
        val job = recent.firstOrNull { (it.payload["topic_id"] as? JsonPrimitive)?.content == topicId } ?: return null
        val age = System.currentTimeMillis() - (TimeUtil.parse(job.updatedAt) ?: 0L)
        if (job.status == "done" && age > 10 * 60_000L) return null
        if (job.status == "failed" && age > 24 * 3_600_000L) return null
        val summary = (job.result?.get("summary") as? JsonPrimitive)?.content.orEmpty()
        val mode = (job.payload["mode"] as? JsonPrimitive)?.content ?: "merge"
        return TopicJob(job.id, mode, job.status, job.error, summary)
    }

    // ------------------------------------------------------------------ choosing

    fun select(topicId: String?) {
        if (selected.value != topicId) tts.stop()
        selected.value = topicId
    }

    fun setExam(value: String?) {
        exam.value = value
    }

    fun setQuery(value: String) {
        query.value = value.take(80)
    }

    fun dismissMessage() {
        message.value = null
    }

    // ------------------------------------------------------------------ the note itself

    /** Saves the owner's text. Marking it as owner-edited protects it from being overwritten by the AI. */
    fun saveText(note: Note, text: String) {
        viewModelScope.launch {
            store.update(Tables.Notes, note.id) {
                it.copy(contentMd = text, ownerEdited = true, status = if (text.isBlank()) it.status else "ready")
            }
        }
    }

    /** The AI writes notes from the Library material (queued; works offline). */
    fun makeNotes(topicId: String) = queue(topicId, "generate", "", "Making notes...")

    /** Text the owner typed or pasted is merged into the note. */
    fun addText(topicId: String, text: String) = queue(topicId, "merge", text, "Added to the queue.")

    /** "Report error": the AI checks the note against the material. */
    fun reportError(topicId: String, comment: String) = queue(topicId, "fix", comment, "Sent. You will be told what was found.")

    private fun queue(topicId: String, mode: String, text: String, notice: String) {
        viewModelScope.launch {
            jobs.enqueue("note_merge", jobPayload("topic_id" to topicId, "mode" to mode, "text" to text))
            message.value = "$notice (it runs when the tablet is online)"
        }
    }

    fun setStatus(topicId: String, status: String) {
        viewModelScope.launch { store.update(Tables.Topics, topicId) { it.copy(status = status) } }
    }

    // ------------------------------------------------------------------ listen and ask

    fun listen(md: String) {
        val sentences = NoteLogic.sentencesForSpeech(md)
        if (sentences.isEmpty()) {
            message.value = "There is nothing to read yet."
            return
        }
        tts.speakSentences(sentences)
    }

    fun stopListening() {
        tts.stop()
    }

    fun askAbout(topic: Topic, extra: String = "") {
        val text = if (extra.isBlank()) "Explain \"${topic.title}\" using my notes." else extra
        askDraft.set(text, topic.id)
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }

    // ------------------------------------------------------------------ doubts

    fun addDoubt(topicId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            store.save(NoteTables.Doubts, Doubt(id = TimeUtil.newId(), topicId = topicId, text = clean, createdAt = TimeUtil.nowIso()))
        }
    }

    fun toggleDoubt(doubt: Doubt) {
        viewModelScope.launch { store.update(NoteTables.Doubts, doubt.id) { it.copy(resolved = !it.resolved) } }
    }

    fun deleteDoubt(doubt: Doubt) {
        viewModelScope.launch { store.delete(NoteTables.Doubts, doubt.id) }
    }

    // ------------------------------------------------------------------ earlier versions (needs the network)

    fun openVersions(noteId: String) {
        versions.value = VersionsUi(noteId)
        viewModelScope.launch {
            val res = apiCall { notesApi.versions(noteId) }
            val found = res.getOrNull()
            versions.update {
                it?.copy(
                    loading = false,
                    current = found?.current ?: 1,
                    versions = found?.versions.orEmpty(),
                    error = if (found == null) describeError(res.exceptionOrNull() ?: IllegalStateException()) else null,
                )
            }
        }
    }

    fun previewVersion(noteId: String, version: Int) {
        viewModelScope.launch {
            val res = apiCall { notesApi.versionText(noteId, version) }
            versions.update { it?.copy(preview = res.getOrNull(), error = res.exceptionOrNull()?.let { e -> describeError(e) }) }
        }
    }

    fun restoreVersion(noteId: String, version: Int) {
        viewModelScope.launch {
            val res = apiCall { notesApi.restore(noteId, version) }
            if (res.isSuccess) {
                SyncScheduler.syncNow(context)
                versions.value = null
                message.value = "Restored version $version. It shows here after the next sync."
            } else {
                versions.update { it?.copy(error = describeError(res.exceptionOrNull() ?: IllegalStateException())) }
            }
        }
    }

    fun closeVersions() {
        versions.value = null
    }
}

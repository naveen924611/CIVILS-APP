package com.naveen.civilscompanion.ui.syllabus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.SyllabusImport
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.ui.ask.AskDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapState(
    val topics: List<Topic> = emptyList(),
    val tree: List<TNode> = emptyList(),
    val exam: String? = null, // null = both exams, otherwise "UPSC" or "APPSC"
    val overall: Int = 0,
    val imports: List<SyllabusImport> = emptyList(), // not approved yet
    val importJobs: List<Job> = emptyList(), // syllabus_import jobs still waiting or failed
    val message: String? = null,
)

/** Syllabus map (spec 6.20): the approved topics as a tree with progress, importance and coverage. */
@HiltViewModel
class SyllabusViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val askDraft: AskDraft,
) : ViewModel() {
    private val exam = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<MapState> = combine(
        store.observe(Tables.Topics),
        store.observe(Tables.SyllabusImports),
        jobs.observeRecent("syllabus_import", 10),
        exam,
        message,
    ) { topics, imports, importJobs, examNow, msg ->
        val tree = TopicTree.build(topics, examNow)
        MapState(
            topics = topics,
            tree = tree,
            exam = examNow,
            overall = TopicTree.coverage(tree),
            imports = imports.filter { it.status != "approved" }.sortedBy { it.title },
            importJobs = importJobs.filter { it.status == "queued" || it.status == "running" || it.status == "failed" },
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapState())

    fun setExam(value: String?) {
        exam.value = value
    }

    fun dismissMessage() {
        message.value = null
    }

    fun setStatus(topicId: String, status: String) {
        viewModelScope.launch { store.update(Tables.Topics, topicId) { it.copy(status = status) } }
    }

    /** Documents of the Library the owner can pick as the syllabus source. */
    suspend fun documents(): List<LibDocument> = store.list(Tables.Documents, RecordQuery(limit = 300))

    /** Queues the import. It works offline: the server reads the syllabus when the tablet is next online. */
    fun startImport(examName: String, title: String, text: String, documentId: String?) {
        viewModelScope.launch {
            jobs.enqueue(
                "syllabus_import",
                jobPayload(
                    "exam" to examName.trim(),
                    "title" to title.trim(),
                    "text" to text.trim().ifEmpty { null },
                    "document_id" to documentId,
                ),
            )
            message.update { "Your syllabus was sent. It appears here when the server has read it (needs internet)." }
        }
    }

    fun askAbout(topic: Topic) {
        askDraft.set("Explain \"${topic.title}\" for my exam preparation.", topic.id)
    }
}

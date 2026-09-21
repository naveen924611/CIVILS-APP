package com.naveen.civilscompanion.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.MockTest
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many mistakes are in the book and how many are due for a retest. */
data class MistakeCounts(val open: Int = 0, val due: Int = 0)

private val TEST_JOB_TYPES = setOf("mock_test", "test_generate", "pyq_import")

@HiltViewModel
class TestsViewModel @Inject constructor(
    private val repo: TestsRepository,
    private val jobs: JobRepository,
    private val store: RecordStore,
) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val tests: StateFlow<List<MockTest>> = repo.observeTests().stateIn(viewModelScope, started, emptyList())

    val topics: StateFlow<List<Topic>> = repo.observeTopics()
        .map { list -> list.filter { it.level >= 2 }.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, started, emptyList())

    val documents: StateFlow<List<LibDocument>> = store.observe(Tables.Documents, RecordQuery(order = Order.NewestFirst, limit = 300))
        .map { list -> list.filter { it.processingStatus == "processed" } }
        .stateIn(viewModelScope, started, emptyList())

    val counts: StateFlow<MistakeCounts> = repo.observeMistakes().map { list ->
        val now = System.currentTimeMillis()
        val open = list.filter { !it.resolved }
        MistakeCounts(open = open.size, due = open.count { MistakeRules.isDue(it, now) })
    }.stateIn(viewModelScope, started, MistakeCounts())

    /** Test jobs that are waiting, running or failed (finished ones show up as tests). */
    val pending: StateFlow<List<Job>> = jobs.observeRecent(null, 60)
        .map { list -> list.filter { it.type in TEST_JOB_TYPES && (it.status == "queued" || it.status == "running" || it.status == "failed") } }
        .stateIn(viewModelScope, started, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun makeWeekly() = enqueue("mock_test", "Your weekly mock test will be made when you are online.", "kind" to "weekly")

    fun makeTopicTest(topicId: String, count: Int) = enqueue(
        "test_generate", "The test will be made when you are online.",
        "kind" to "topic", "topic_id" to topicId, "count" to count,
    )

    /** Aptitude drill: exact sums and reasoning made by the server. area null = mixed. */
    fun makeAptitude(area: String?, count: Int) {
        val pairs = mutableListOf<Pair<String, Any?>>("kind" to "aptitude", "count" to count)
        if (!area.isNullOrBlank()) pairs.add("area" to area)
        enqueue("test_generate", "The drill will be made when you are online.", *pairs.toTypedArray())
    }

    fun makePastPaper(exam: String, year: Int, paper: String) = enqueue(
        "test_generate", "The past paper will be made when you are online.",
        "kind" to "past_paper", "exam" to exam.trim(), "year" to year, "paper" to paper.trim(),
    )

    fun importPaper(documentId: String, exam: String, year: Int, paper: String) = enqueue(
        "pyq_import", "The paper will be read when you are online. Then you can make a past-paper test from it.",
        "document_id" to documentId, "exam" to exam.trim(), "year" to year, "paper" to paper.trim(),
    )

    fun dismiss(job: Job) {
        viewModelScope.launch { store.delete(Tables.Jobs, job.id) }
    }

    private fun enqueue(type: String, note: String, vararg pairs: Pair<String, Any?>) {
        viewModelScope.launch {
            jobs.enqueue(type, jobPayload(*pairs))
            _message.value = note
        }
    }
}

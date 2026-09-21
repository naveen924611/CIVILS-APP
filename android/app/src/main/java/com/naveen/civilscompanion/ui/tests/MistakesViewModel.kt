package com.naveen.civilscompanion.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.Mistake
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One entry of the mistake book with its question and subject. */
data class MistakeRow(val mistake: Mistake, val mcq: Mcq, val subject: String, val due: Boolean)

data class MistakeBookState(
    val rows: List<MistakeRow> = emptyList(),
    val subjects: List<String> = emptyList(),
    val cleared: Int = 0,
    val due: Int = 0,
    val total: Int = 0,
)

@HiltViewModel
class MistakesViewModel @Inject constructor(private val repo: TestsRepository) : ViewModel() {
    val subject = MutableStateFlow<String?>(null)
    val type = MutableStateFlow<String?>(null)

    private val all: StateFlow<MistakeBookState> = repo.observeMistakes().map { list ->
        val now = System.currentTimeMillis()
        val topics = repo.topics()
        val open = list.filter { !it.resolved }
        val rows = open.mapNotNull { m ->
            val q = repo.mcq(m.mcqId) ?: return@mapNotNull null
            MistakeRow(m, q, TestLogic.subjectOf(q.topicId, topics), MistakeRules.isDue(m, now))
        }.sortedWith(compareByDescending<MistakeRow> { it.due }.thenBy { it.subject })
        MistakeBookState(
            rows = rows, subjects = rows.map { it.subject }.distinct().sorted(), cleared = list.count { it.resolved },
            due = rows.count { it.due }, total = rows.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MistakeBookState())

    /** The book after the subject and type filters. */
    val state: StateFlow<MistakeBookState> = combine(all, subject, type) { s, sub, t ->
        s.copy(rows = s.rows.filter { (sub == null || it.subject == sub) && (t == null || it.mistake.mistakeType == t) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MistakeBookState())
}

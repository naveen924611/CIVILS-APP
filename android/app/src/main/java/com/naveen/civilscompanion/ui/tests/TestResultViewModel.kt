package com.naveen.civilscompanion.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ResultState(
    val loading: Boolean = true,
    val error: String? = null,
    val testId: String = "",
    val title: String = "",
    val analysis: Analysis? = null,
    val items: List<Answered> = emptyList(),
)

/** The score, the analysis and the review of a finished test (or of the mistakes retest, id "mistakes"). */
@HiltViewModel
class TestResultViewModel @Inject constructor(private val repo: TestsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ResultState())
    val state: StateFlow<ResultState> = _state.asStateFlow()

    private var negative = false
    private var loadedId: String? = null

    fun load(testId: String) {
        if (loadedId == testId) return
        loadedId = testId
        viewModelScope.launch {
            val run = repo.run(testId)
            val test = if (testId == RETEST_ID) null else repo.test(testId)
            val ids = test?.mcqIds ?: run?.questionIds ?: emptyList()
            val questions = repo.questions(ids)
            if (questions.isEmpty()) {
                _state.value = ResultState(loading = false, testId = testId, error = "There is no result to show yet.")
                return@launch
            }
            negative = if (run != null && run.finished) run.negative else test?.negativeMarking ?: false
            val items = repo.answered(testId, questions)
            val taken = items.any { !it.skipped } || run?.finished == true || test?.status == "done" || test?.status == "analysed"
            if (!taken) {
                _state.value = ResultState(loading = false, testId = testId, error = "You have not taken this test yet.")
                return@launch
            }
            publish(testId, test?.title ?: run?.title ?: "Test", items)
        }
    }

    private suspend fun publish(testId: String, title: String, items: List<Answered>) {
        _state.value = ResultState(
            loading = false, testId = testId, title = title, items = items,
            analysis = TestLogic.analyse(items, repo.topics(), negative),
        )
    }

    /** The owner says what kind of mistake a wrong answer was. */
    fun setType(mcqId: String, type: String) {
        val s = _state.value
        viewModelScope.launch {
            repo.setMistakeType(s.testId, mcqId, type)
            publish(s.testId, s.title, s.items.map { if (it.mcq.id == mcqId) it.copy(mistakeType = type) else it })
        }
    }
}

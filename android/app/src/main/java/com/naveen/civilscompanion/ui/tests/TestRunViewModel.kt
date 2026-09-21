package com.naveen.civilscompanion.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Mcq
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RunState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val questions: List<Mcq> = emptyList(),
    val run: LocalTestRun? = null,
    /** The server test was taken before: offer the result or a practice retake. */
    val alreadyDone: Boolean = false,
    val isRealTest: Boolean = true,
)

/** Takes one test (a server test, or the mistakes retest with id "mistakes"). Every answer is saved at once. */
@HiltViewModel
class TestRunViewModel @Inject constructor(private val repo: TestsRepository) : ViewModel() {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the test id when the result is ready to show. */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private var loadedId: String? = null
    private var finishing = false

    fun load(testId: String) {
        if (loadedId == testId) return
        loadedId = testId
        viewModelScope.launch {
            if (testId == RETEST_ID) loadRetest() else loadTest(testId)
        }
    }

    private suspend fun loadTest(testId: String) {
        val test = repo.test(testId)
        if (test == null) {
            _state.value = RunState(loading = false, error = "This test is not on the tablet yet. Connect to the internet once so it can download.")
            return
        }
        val questions = repo.questions(test.mcqIds)
        if (questions.isEmpty()) {
            _state.value = RunState(loading = false, error = "The questions are still downloading. Connect to the internet and try again in a minute.")
            return
        }
        val saved = repo.run(testId)
        val fresh = LocalTestRun(
            id = testId, title = test.title, kind = test.kind, questionIds = questions.map { it.id },
            durationMin = test.durationMin, negative = test.negativeMarking,
        )
        val taken = test.status == "done" || test.status == "analysed"
        val resume = saved != null && !saved.finished && saved.startedAtMs > 0
        _state.value = RunState(
            loading = false, title = test.title, questions = questions, run = if (resume) saved else fresh,
            alreadyDone = taken && !resume, isRealTest = true,
        )
    }

    private suspend fun loadRetest() {
        val saved = repo.run(RETEST_ID)
        if (saved != null && !saved.finished && saved.startedAtMs > 0) {
            val questions = repo.questions(saved.questionIds)
            if (questions.isNotEmpty()) {
                _state.value = RunState(loading = false, title = saved.title, questions = questions, run = saved, isRealTest = false)
                return
            }
        }
        val ids = repo.retestIds(20)
        val questions = repo.questions(ids)
        if (questions.isEmpty()) {
            _state.value = RunState(loading = false, error = "Your mistake book is empty, so there is nothing to retest.", isRealTest = false)
            return
        }
        val run = LocalTestRun(
            id = RETEST_ID, title = "Mistakes retest", kind = "mistakes", questionIds = questions.map { it.id },
            durationMin = maxOf(5, questions.size),
        )
        _state.value = RunState(loading = false, title = run.title, questions = questions, run = run, isRealTest = false)
    }

    private fun update(change: (LocalTestRun) -> LocalTestRun) {
        val current = _state.value.run ?: return
        val next = change(current)
        _state.value = _state.value.copy(run = next)
        viewModelScope.launch { repo.saveRun(next) }
    }

    fun start(negative: Boolean) {
        update {
            it.copy(
                startedAtMs = System.currentTimeMillis(), negative = negative, finished = false, index = 0,
                chosen = emptyMap(), confidence = emptyMap(), flagged = emptyList(), mistakeTypes = emptyMap(),
            )
        }
        _state.value = _state.value.copy(alreadyDone = false)
    }

    fun choose(mcqId: String, option: Int) = update { run ->
        if (run.chosen[mcqId] == option) run.copy(chosen = run.chosen - mcqId, confidence = run.confidence - mcqId)
        else run.copy(chosen = run.chosen + (mcqId to option))
    }

    fun setConfidence(mcqId: String, value: String) = update { it.copy(confidence = it.confidence + (mcqId to value)) }

    fun toggleFlag(mcqId: String) = update {
        it.copy(flagged = if (mcqId in it.flagged) it.flagged - mcqId else it.flagged + mcqId)
    }

    fun goTo(index: Int) = update { it.copy(index = index.coerceIn(0, (it.questionIds.size - 1).coerceAtLeast(0))) }

    fun finish() {
        val s = _state.value
        val run = s.run ?: return
        if (finishing || run.startedAtMs == 0L) return
        finishing = true
        viewModelScope.launch {
            repo.finish(run, s.questions, s.isRealTest)
            _finished.tryEmit(run.id)
        }
    }
}

package com.naveen.civilscompanion.ui.tests

import com.naveen.civilscompanion.data.model.Attempt
import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.MockTest
import com.naveen.civilscompanion.data.model.Mistake
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Everything the test screens read and write. Tests and MCQs come from the server through sync; the owner's answers,
 * the test result and the mistake book are written here and sent back by sync.
 *
 * Other screens that grade a question (brief quizzes, note questions) can call [recordAnswer] so the mistake book
 * ("populated from every graded MCQ") stays complete.
 */
@Singleton
class TestsRepository @Inject constructor(private val store: RecordStore) {

    fun observeTests(): Flow<List<MockTest>> =
        store.observe(Tables.Tests, RecordQuery(limit = 300)).map { list ->
            list.sortedByDescending { TimeUtil.parse(it.scheduledFor) ?: TimeUtil.parse(it.updatedAt) ?: 0L }
        }

    fun observeMistakes(): Flow<List<Mistake>> = store.observe(Tables.Mistakes, RecordQuery(limit = 2000))

    fun observeTopics(): Flow<List<Topic>> = store.observe(Tables.Topics, RecordQuery(limit = 5000))

    suspend fun test(id: String): MockTest? = store.get(Tables.Tests, id)

    suspend fun topics(): Map<String, Topic> = store.list(Tables.Topics, RecordQuery(limit = 5000)).associateBy { it.id }

    suspend fun mcq(id: String): Mcq? = store.get(Tables.Mcqs, id)

    /** The questions of a list of ids, in that order (a question the tablet has not received yet is skipped). */
    suspend fun questions(ids: List<String>): List<Mcq> = ids.mapNotNull { store.get(Tables.Mcqs, it) }

    suspend fun run(id: String): LocalTestRun? = store.get(TestLocalTables.Runs, id)

    suspend fun saveRun(run: LocalTestRun) {
        store.save(TestLocalTables.Runs, run)
    }

    suspend fun mistakes(): List<Mistake> = store.list(Tables.Mistakes, RecordQuery(limit = 2000))

    suspend fun mistakeOf(mcqId: String): Mistake? = store.list(Tables.Mistakes, RecordQuery(k1 = mcqId)).firstOrNull()

    /** Ids for a mistakes retest. */
    suspend fun retestIds(limit: Int = 20): List<String> = MistakeRules.retestIds(mistakes(), System.currentTimeMillis(), limit)

    /** The answers of a finished test as [Answered] rows (from this tablet's run, else from the synced attempts). */
    suspend fun answered(testId: String, questions: List<Mcq>): List<Answered> {
        val run = run(testId)
        if (run != null && run.finished) {
            return questions.map { q ->
                val chosen = run.chosen[q.id] ?: -1
                val conf = run.confidence[q.id] ?: ""
                val type = run.mistakeTypes[q.id] ?: ""
                Answered(q, chosen, conf, if (chosen >= 0 && chosen != q.answerIndex && type.isBlank()) TestLogic.defaultMistakeType(conf) else type)
            }
        }
        val attempts = store.list(Tables.Attempts, RecordQuery(k2 = testId)).groupBy { it.mcqId }
            .mapValues { (_, rows) -> rows.maxByOrNull { TimeUtil.parse(it.at) ?: 0L } }
        return questions.map { q ->
            val a = attempts[q.id]
            Answered(q, a?.chosen ?: -1, a?.confidence ?: "", a?.mistakeType ?: "")
        }
    }

    /**
     * Grades a finished run: one Attempt per question, the mistake book, and (for a real test) the test row.
     * Returns the analysis. The run is saved as finished.
     */
    suspend fun finish(run: LocalTestRun, questions: List<Mcq>, isRealTest: Boolean): Analysis {
        val now = System.currentTimeMillis()
        val nowIso = TimeUtil.toIso(now)
        val answered = questions.map { q ->
            val chosen = run.chosen[q.id] ?: -1
            val conf = run.confidence[q.id] ?: ""
            val wrong = chosen >= 0 && chosen != q.answerIndex
            Answered(q, chosen, conf, if (wrong) (run.mistakeTypes[q.id] ?: TestLogic.defaultMistakeType(conf)) else "")
        }
        val testId = if (isRealTest) run.id else null
        store.saveAll(
            Tables.Attempts,
            answered.map {
                Attempt(
                    id = TimeUtil.newId(), mcqId = it.mcq.id, testId = testId, chosen = it.chosen, correct = it.correct,
                    confidence = it.confidence, mistakeType = it.mistakeType, at = nowIso,
                )
            },
        )
        answered.filter { !it.skipped }.forEach { recordMistakeBook(it.mcq.id, it.chosen, it.correct, it.mistakeType, now) }
        val analysis = TestLogic.analyse(answered, topics(), run.negative)
        if (isRealTest) {
            store.update(Tables.Tests, run.id) {
                it.copy(
                    status = "done", score = analysis.score, negativeMarking = run.negative,
                    startedAt = if (run.startedAtMs > 0) TimeUtil.toIso(run.startedAtMs) else nowIso, finishedAt = nowIso,
                )
            }
        }
        saveRun(run.copy(finished = true, finishedAtMs = now, mistakeTypes = answered.filter { it.mistakeType.isNotBlank() }.associate { it.mcq.id to it.mistakeType }))
        return analysis
    }

    /** For any screen that grades one question: puts it in (or moves it along in) the mistake book. */
    suspend fun recordAnswer(mcq: Mcq, chosen: Int, confidence: String = "") {
        val correct = chosen >= 0 && chosen == mcq.answerIndex
        val type = if (correct || chosen < 0) "" else TestLogic.defaultMistakeType(confidence)
        if (chosen >= 0) recordMistakeBook(mcq.id, chosen, correct, type, System.currentTimeMillis())
    }

    private suspend fun recordMistakeBook(mcqId: String, chosen: Int, correct: Boolean, type: String, nowMs: Long) {
        val existing = mistakeOf(mcqId)
        if (correct) {
            if (existing == null) return
            val next = MistakeRules.onCorrect(existing, nowMs)
            if (next != existing) store.save(Tables.Mistakes, next)
        } else {
            store.save(Tables.Mistakes, MistakeRules.onWrong(existing, TimeUtil.newId(), mcqId, chosen, type, nowMs))
        }
    }

    /** The owner changes the type of a mistake on the review screen. */
    suspend fun setMistakeType(testId: String, mcqId: String, type: String) {
        val run = run(testId)
        if (run != null) saveRun(run.copy(mistakeTypes = run.mistakeTypes + (mcqId to type)))
        mistakeOf(mcqId)?.let { store.save(Tables.Mistakes, it.copy(mistakeType = type)) }
        store.list(Tables.Attempts, RecordQuery(k1 = mcqId, k2 = testId)).forEach { a ->
            if (!a.correct && a.chosen >= 0) store.save(Tables.Attempts, a.copy(mistakeType = type))
        }
    }
}

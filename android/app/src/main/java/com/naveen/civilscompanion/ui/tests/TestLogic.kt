package com.naveen.civilscompanion.ui.tests

import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.Mistake
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.TimeUtil

/** One answered (or skipped) question of a test. chosen -1 = skipped. */
data class Answered(val mcq: Mcq, val chosen: Int, val confidence: String, val mistakeType: String) {
    val skipped: Boolean get() = chosen < 0
    val correct: Boolean get() = chosen >= 0 && chosen == mcq.answerIndex
    val wrong: Boolean get() = chosen >= 0 && chosen != mcq.answerIndex
}

data class SubjectScore(val subject: String, val total: Int, val correct: Int, val wrong: Int, val skipped: Int)

data class ConfidenceBucket(val answered: Int, val correct: Int)

data class Analysis(
    val total: Int,
    val correct: Int,
    val wrong: Int,
    val skipped: Int,
    val score: Double,
    val negative: Boolean,
    val accuracy: Double,
    val subjects: List<SubjectScore>,
    val mistakes: Map<String, Int>,
    val confidence: Map<String, ConfidenceBucket>,
    val guessMessage: String,
    val tips: List<String>,
    /** Topics to study again: (title, number of questions you did not know). */
    val studyTopics: List<Pair<String, Int>>,
)

/** Pure scoring and analysis (no Android classes). Mirrors backend/app/features/tests/scoring.py. */
object TestLogic {
    const val NEGATIVE_FRACTION = 1.0 / 3.0
    const val DIDNT_KNOW = "didnt_know"
    const val CONFUSED = "confused"
    const val SILLY = "silly"
    val MISTAKE_TYPES = listOf(DIDNT_KNOW, CONFUSED, SILLY)
    val CONFIDENCES = listOf("sure", "unsure", "guess")

    private val tips = mapOf(
        DIDNT_KNOW to "Topics you did not know are added to your revision: open the mistake book and study them.",
        CONFUSED to "You narrowed some questions to two options. Comparison cards are made so you can tell them apart.",
        SILLY to "Some mistakes were slips although you felt sure. Read every option slowly before you choose.",
    )

    /** 1 mark per right answer, minus one third per wrong answer when negative marking is on. */
    fun marks(correct: Int, wrong: Int, negative: Boolean): Double {
        val raw = correct - if (negative) wrong * NEGATIVE_FRACTION else 0.0
        return Math.round(raw * 100.0) / 100.0
    }

    fun scoreText(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else String.format(java.util.Locale.ENGLISH, "%.2f", value).trimEnd('0')

    /** guess or not marked -> didn't know, unsure -> confused two options, sure -> silly slip. */
    fun defaultMistakeType(confidence: String?): String = when ((confidence ?: "").trim().lowercase()) {
        "sure" -> SILLY
        "unsure" -> CONFUSED
        else -> DIDNT_KNOW
    }

    fun mistakeLabel(type: String): String = when (type) {
        DIDNT_KNOW -> "Did not know"
        CONFUSED -> "Confused two options"
        SILLY -> "Silly mistake"
        else -> "Mistake"
    }

    fun confidenceLabel(value: String): String = when (value) {
        "sure" -> "Sure"
        "unsure" -> "Unsure"
        "guess" -> "Guess"
        else -> "Not marked"
    }

    /** The subject (level 1 ancestor) of a topic, "Other" when unknown. */
    fun subjectOf(topicId: String?, topics: Map<String, Topic>): String {
        if (topicId == null) return "Other"
        val first = topics[topicId] ?: return "Other"
        val chain = ArrayList<Topic>()
        val seen = HashSet<String>()
        var cur: Topic? = first
        while (cur != null && seen.add(cur.id)) {
            chain.add(cur)
            val parent = cur.parentId
            cur = if (parent == null) null else topics[parent]
        }
        val subject = chain.firstOrNull { it.level == 1 }
        if (subject != null) return subject.title
        val below = chain.filter { it.level != 0 }
        return (below.lastOrNull() ?: chain.first()).title
    }

    fun analyse(items: List<Answered>, topics: Map<String, Topic>, negative: Boolean): Analysis {
        val total = items.size
        val correct = items.count { it.correct }
        val skipped = items.count { it.skipped }
        val wrong = items.count { it.wrong }
        val subjects = LinkedHashMap<String, IntArray>() // total, correct, wrong, skipped
        val mistakes = MISTAKE_TYPES.associateWith { 0 }.toMutableMap()
        val conf = (CONFIDENCES + "unmarked").associateWith { intArrayOf(0, 0) }
        val study = HashMap<String, Int>()
        for (a in items) {
            val row = subjects.getOrPut(subjectOf(a.mcq.topicId, topics)) { IntArray(4) }
            row[0]++
            val bucket = conf.getValue(if (a.confidence in CONFIDENCES) a.confidence else "unmarked")
            if (!a.skipped) {
                bucket[0]++
                if (a.correct) bucket[1]++
            }
            when {
                a.correct -> row[1]++
                a.skipped -> row[3]++
                else -> {
                    row[2]++
                    val kind = if (a.mistakeType in MISTAKE_TYPES) a.mistakeType else defaultMistakeType(a.confidence)
                    mistakes[kind] = (mistakes[kind] ?: 0) + 1
                    val tid = a.mcq.topicId
                    if (kind == DIDNT_KNOW && tid != null) study[tid] = (study[tid] ?: 0) + 1
                }
            }
        }
        val guesses = conf.getValue("guess")[0]
        val guessRight = conf.getValue("guess")[1]
        val net = marks(guessRight, guesses - guessRight, true)
        val message = if (guesses == 0) "You did not mark any answer as a guess." else
            "You guessed $guesses question${if (guesses != 1) "s" else ""} and got $guessRight right. " +
                "With negative marking those guesses would give ${scoreText(net)} marks."
        return Analysis(
            total = total, correct = correct, wrong = wrong, skipped = skipped,
            score = marks(correct, wrong, negative), negative = negative,
            accuracy = if (total == 0) 0.0 else correct.toDouble() / total,
            subjects = subjects.map { (name, r) -> SubjectScore(name, r[0], r[1], r[2], r[3]) }
                .sortedWith(compareByDescending<SubjectScore> { it.total }.thenBy { it.subject }),
            mistakes = mistakes,
            confidence = conf.mapValues { ConfidenceBucket(it.value[0], it.value[1]) },
            guessMessage = message,
            tips = MISTAKE_TYPES.filter { (mistakes[it] ?: 0) > 0 }.mapNotNull { tips[it] },
            studyTopics = study.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { (topics[it.key]?.title ?: "") to it.value }.filter { it.first.isNotBlank() },
        )
    }

    /** "mm:ss" for the countdown (never negative). */
    fun clock(seconds: Long): String {
        val s = if (seconds < 0) 0 else seconds
        return String.format(java.util.Locale.ENGLISH, "%02d:%02d", s / 60, s % 60)
    }

    fun remainingSeconds(startedAtMs: Long, durationMin: Int, nowMs: Long): Long =
        (startedAtMs + durationMin * 60_000L - nowMs) / 1000

    fun percent(part: Int, whole: Int): Int = if (whole <= 0) 0 else Math.round(part * 100.0 / whole).toInt()
}

/** The mistake book rules (spec 6.23): a wrong answer adds it; two right answers in a row, spaced apart, remove it. */
object MistakeRules {
    const val DAY_MS = 86_400_000L
    const val FIRST_GAP_DAYS = 1
    const val NEXT_GAP_DAYS = 3
    const val NEEDED_STREAK = 2

    fun onWrong(existing: Mistake?, newId: String, mcqId: String, chosen: Int, type: String, nowMs: Long): Mistake {
        val base = existing ?: Mistake(id = newId, mcqId = mcqId)
        return base.copy(
            mistakeType = type, yourAnswer = chosen, streak = 0, resolved = false,
            lastAnsweredAt = TimeUtil.toIso(nowMs), nextDueAt = TimeUtil.toIso(nowMs + FIRST_GAP_DAYS * DAY_MS),
        )
    }

    /** Returns the changed row, or the same row when the answer came too soon to count. */
    fun onCorrect(existing: Mistake, nowMs: Long): Mistake {
        if (existing.resolved) return existing
        val due = TimeUtil.parse(existing.nextDueAt)
        if (due != null && nowMs < due) return existing
        val streak = existing.streak + 1
        return if (streak >= NEEDED_STREAK) {
            existing.copy(streak = streak, resolved = true, lastAnsweredAt = TimeUtil.toIso(nowMs), nextDueAt = null)
        } else {
            existing.copy(
                streak = streak, lastAnsweredAt = TimeUtil.toIso(nowMs), nextDueAt = TimeUtil.toIso(nowMs + NEXT_GAP_DAYS * DAY_MS),
            )
        }
    }

    fun isDue(m: Mistake, nowMs: Long): Boolean {
        if (m.resolved) return false
        val due = TimeUtil.parse(m.nextDueAt) ?: return true
        return due <= nowMs
    }

    /** Question ids for a retest: due ones first (oldest first), then the others, no repeats. */
    fun retestIds(all: List<Mistake>, nowMs: Long, limit: Int = 20, dueOnly: Boolean = false): List<String> {
        val open = all.filter { !it.resolved }
        val due = open.filter { isDue(it, nowMs) }.sortedBy { TimeUtil.parse(it.nextDueAt) ?: 0L }
        val later = if (dueOnly) emptyList() else open.filter { !isDue(it, nowMs) }.sortedBy { TimeUtil.parse(it.nextDueAt) ?: 0L }
        return (due + later).map { it.mcqId }.distinct().take(limit)
    }
}

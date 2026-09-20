package com.naveen.civilscompanion.srs

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * FSRS-6 memory model, a plain Kotlin port of the server's backend/app/srs/fsrs6.py. No Android classes here, so it
 * runs in JVM unit tests: FsrsVectorsTest checks it against data/fsrs_vectors.json (made with the reference package).
 * Keep the two files identical in behaviour:
 *   - every interval is a whole number of days (at least 1), no learning steps, no fuzzing;
 *   - default FSRS-6 weights (21 numbers), desired retention 0.9;
 *   - elapsed time for retrievability is counted in WHOLE days (rounded down); a review less than one day after the
 *     previous one uses the "same-day" stability formula.
 * Grades: 1 Again, 2 Hard, 3 Good, 4 Easy.
 */

const val GRADE_AGAIN = 1
const val GRADE_HARD = 2
const val GRADE_GOOD = 3
const val GRADE_EASY = 4

/** Time text used inside card states: "2026-09-20T10:00:00.000Z" (UTC, milliseconds). */
object FsrsTime {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = formatter.format(instant.truncatedTo(ChronoUnit.MILLIS))

    /** ISO text with Z or an offset -> Instant, or null when it cannot be read. */
    fun parse(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * What FSRS remembers about one card (stored in Card.fsrsState). stability: days for recall to fall to 90 percent;
 * difficulty: 1 easy to 10 hard; both null for a card never reviewed ("new").
 */
data class CardMemory(
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant? = null,
    val lastReview: Instant? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = stability == null || difficulty == null

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "stability" to (stability?.let { JsonPrimitive(it) } ?: JsonNull),
            "difficulty" to (difficulty?.let { JsonPrimitive(it) } ?: JsonNull),
            "due" to (due?.let { JsonPrimitive(FsrsTime.format(it)) } ?: JsonNull),
            "last_review" to (lastReview?.let { JsonPrimitive(FsrsTime.format(it)) } ?: JsonNull),
            "reps" to JsonPrimitive(reps),
            "lapses" to JsonPrimitive(lapses),
        ),
    )

    companion object {
        /** Reads a stored state. null (or an object without stability) gives a new card. */
        fun fromJson(o: JsonObject?): CardMemory {
            if (o == null) return CardMemory()
            fun num(key: String): Double? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
            fun time(key: String): Instant? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { FsrsTime.parse(it.content) }
            fun count(key: String): Int = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0
            return CardMemory(
                stability = num("stability"),
                difficulty = num("difficulty"),
                due = time("due"),
                lastReview = time("last_review"),
                reps = count("reps"),
                lapses = count("lapses"),
            )
        }
    }
}

/** Result of grading a card, before it is turned into a CardMemory. */
private data class Step(val stability: Double, val difficulty: Double, val intervalDays: Int, val reps: Int, val lapses: Int)

class Fsrs(
    weights: List<Double> = DEFAULT_WEIGHTS,
    val retention: Double = DEFAULT_RETENTION,
    val maxInterval: Int = MAX_INTERVAL_DAYS,
) {
    private val w: DoubleArray = weights.toDoubleArray()
    private val decay: Double
    private val factor: Double

    init {
        require(w.size == 21) { "FSRS-6 needs 21 weights, got ${w.size}" }
        require(retention in 0.5..0.99) { "retention must be between 0.5 and 0.99" }
        decay = -w[20]
        factor = 0.9.pow(1.0 / decay) - 1.0
    }

    /** Chance (0..1) to recall the card at `now`. 0 for a card never reviewed. */
    fun retrievability(state: CardMemory?, now: Instant): Double {
        if (state == null || state.isNew) return 0.0
        val last = state.lastReview ?: return 0.0
        val stability = state.stability ?: return 0.0
        val elapsed = max(0L, wholeDays(last, now)).toDouble()
        return (1.0 + factor * elapsed / stability).pow(decay)
    }

    /** Days until recall falls to the desired retention (whole days, 1 .. maxInterval). */
    fun intervalFor(stability: Double): Int {
        val days = (stability / factor) * (retention.pow(1.0 / decay) - 1.0)
        val rounded = Math.rint(days) // half to even, like Python's round()
        val whole = if (rounded >= maxInterval.toDouble()) maxInterval else rounded.toInt()
        return min(max(whole, 1), maxInterval)
    }

    private fun initialStability(grade: Int): Double = max(w[grade - 1], STABILITY_MIN)

    private fun initialDifficulty(grade: Int, clamp: Boolean): Double {
        val d = w[4] - Math.E.pow(w[5] * (grade - 1)) + 1.0
        return if (clamp) min(max(d, DIFFICULTY_MIN), DIFFICULTY_MAX) else d
    }

    private fun nextDifficulty(difficulty: Double, grade: Int): Double {
        val arg1 = initialDifficulty(GRADE_EASY, clamp = false)
        val delta = -(w[6] * (grade - 3))
        val arg2 = difficulty + (10.0 - difficulty) * delta / 9.0
        val next = w[7] * arg1 + (1.0 - w[7]) * arg2
        return min(max(next, DIFFICULTY_MIN), DIFFICULTY_MAX)
    }

    private fun shortTermStability(stability: Double, grade: Int): Double {
        var inc = Math.E.pow(w[17] * (grade - 3 + w[18])) * stability.pow(-w[19])
        if (grade >= GRADE_HARD) inc = max(inc, 1.0)
        return max(stability * inc, STABILITY_MIN)
    }

    private fun nextStability(difficulty: Double, stability: Double, r: Double, grade: Int): Double {
        val next: Double
        if (grade == GRADE_AGAIN) {
            val longTerm = w[11] * difficulty.pow(-w[12]) * ((stability + 1.0).pow(w[13]) - 1.0) * Math.E.pow((1.0 - r) * w[14])
            val shortTerm = stability / Math.E.pow(w[17] * w[18])
            next = min(longTerm, shortTerm)
        } else {
            val hardPenalty = if (grade == GRADE_HARD) w[15] else 1.0
            val easyBonus = if (grade == GRADE_EASY) w[16] else 1.0
            next = stability * (
                1.0 + Math.E.pow(w[8]) * (11.0 - difficulty) * stability.pow(-w[9]) *
                    (Math.E.pow((1.0 - r) * w[10]) - 1.0) * hardPenalty * easyBonus
                )
        }
        return max(next, STABILITY_MIN)
    }

    private fun step(state: CardMemory?, grade: Int, now: Instant): Step {
        require(grade in 1..4) { "grade must be 1, 2, 3 or 4, got $grade" }
        val reps = state?.reps ?: 0
        var lapses = state?.lapses ?: 0
        val stability: Double
        val difficulty: Double
        if (state == null || state.isNew) {
            stability = initialStability(grade)
            difficulty = initialDifficulty(grade, clamp = true)
        } else {
            val oldS = state.stability ?: STABILITY_MIN
            val oldD = state.difficulty ?: DIFFICULTY_MIN
            val last = state.lastReview
            val days: Long? = if (last != null) wholeDays(last, now) else null
            stability = if (days != null && days < 1L) {
                shortTermStability(oldS, grade)
            } else {
                nextStability(oldD, oldS, retrievability(state, now), grade)
            }
            difficulty = nextDifficulty(oldD, grade)
            if (grade == GRADE_AGAIN) lapses += 1
        }
        return Step(stability, difficulty, intervalFor(stability), reps + 1, lapses)
    }

    /** The new state after grading the card at `now`. Does not change `state`. */
    fun review(state: CardMemory?, grade: Int, now: Instant): CardMemory {
        val at = now.truncatedTo(ChronoUnit.MILLIS)
        val s = step(state, grade, at)
        return CardMemory(
            stability = s.stability,
            difficulty = s.difficulty,
            due = at.plus(Duration.ofDays(s.intervalDays.toLong())),
            lastReview = at,
            reps = s.reps,
            lapses = s.lapses,
        )
    }

    /** Days until the next review for each grade (shown on the grade buttons). */
    fun nextIntervals(state: CardMemory?, now: Instant): Map<Int, Int> {
        val at = now.truncatedTo(ChronoUnit.MILLIS)
        return (1..4).associateWith { step(state, it, at).intervalDays }
    }

    companion object {
        val DEFAULT_WEIGHTS: List<Double> = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614,
            0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
        const val DEFAULT_RETENTION = 0.9
        const val MAX_INTERVAL_DAYS = 36500
        private const val STABILITY_MIN = 0.001
        private const val DIFFICULTY_MIN = 1.0
        private const val DIFFICULTY_MAX = 10.0

        /** Engine with the owner's retention (KV revision.retention); falls back to the default when it is not allowed. */
        fun forRetention(retention: Double?): Fsrs =
            if (retention != null && retention in 0.5..0.99) Fsrs(DEFAULT_WEIGHTS, retention) else Fsrs()

        /** Whole days from `from` to `to`, rounded down (also for negative gaps), like Python's timedelta.days. */
        internal fun wholeDays(from: Instant, to: Instant): Long =
            Math.floorDiv(to.toEpochMilli() - from.toEpochMilli(), 86_400_000L)
    }
}

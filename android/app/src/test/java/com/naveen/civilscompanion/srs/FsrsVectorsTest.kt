package com.naveen.civilscompanion.srs

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin FSRS must give the same numbers as the reference package (data/fsrs_vectors.json, copied to test resources). */
class FsrsVectorsTest {
    private val vectors: JsonObject = run {
        val stream = FsrsVectorsTest::class.java.classLoader!!.getResourceAsStream("fsrs_vectors.json")
        assertNotNull("fsrs_vectors.json is missing from src/test/resources", stream)
        Json.parseToJsonElement(stream!!.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun at(text: String): Instant = FsrsTime.parse(text)!!

    @Test
    fun reproducesEveryReferenceCase() {
        val cases = vectors["cases"]!!.jsonArray
        assertTrue(cases.size > 10)
        for (caseElement in cases) {
            val case = caseElement.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val weights = case["weights"]!!.jsonArray.map { it.jsonPrimitive.double }
            val engine = Fsrs(weights, case["retention"]!!.jsonPrimitive.double)
            var state: CardMemory? = null
            for (stepElement in case["steps"]!!.jsonArray) {
                val step = stepElement.jsonObject
                val now = at(step["at"]!!.jsonPrimitive.content)
                assertEquals("$name: recall before", step["retrievability_before"]!!.jsonPrimitive.double, engine.retrievability(state, now), 1e-6)
                val got = engine.nextIntervals(state, now)
                for ((grade, days) in step["intervals"]!!.jsonObject) {
                    val expected = days.jsonPrimitive.int
                    assertTrue("$name: interval for grade $grade was ${got[grade.toInt()]}, expected $expected", abs(got[grade.toInt()]!! - expected) <= 1)
                }
                val next = engine.review(state, step["grade"]!!.jsonPrimitive.int, now)
                assertEquals("$name: stability", step["stability"]!!.jsonPrimitive.double, next.stability!!, 1e-6)
                assertEquals("$name: difficulty", step["difficulty"]!!.jsonPrimitive.double, next.difficulty!!, 1e-6)
                assertEquals("$name: interval", step["interval_days"]!!.jsonPrimitive.int.toLong(), Duration.between(now, next.due!!).toDays())
                assertEquals("$name: last review", FsrsTime.format(now), FsrsTime.format(next.lastReview!!))
                state = next
            }
        }
    }

    @Test
    fun reproducesStrengthCases() {
        val engine = Fsrs()
        for (caseElement in vectors["strength_cases"]!!.jsonArray) {
            val case = caseElement.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val now = at(case["now"]!!.jsonPrimitive.content)
            val states: List<CardMemory?> = (case["states"] as JsonArray).map { e ->
                if (e is JsonNull) null else CardMemory.fromJson(e.jsonObject)
            }
            val mem = Strength.topicMemory(engine, states, now)!!
            val expect = case["expect"]!!.jsonObject
            assertEquals("$name: strength", expect["strength"]!!.jsonPrimitive.double, mem.strength, 1e-9)
            assertEquals("$name: coverage", expect["coverage"]!!.jsonPrimitive.double, mem.coverage, 1e-9)
            assertEquals("$name: repeat share", expect["repeat_share"]!!.jsonPrimitive.double, mem.repeatShare, 1e-9)
            assertEquals("$name: status", expect["status"]!!.jsonPrimitive.content, Strength.statusFor(case["status"]!!.jsonPrimitive.content, mem))
        }
    }

    @Test
    fun newCardGetsAnIntervalAtOnce() {
        val engine = Fsrs()
        val now = Instant.parse("2026-09-20T10:00:00Z")
        val intervals = engine.nextIntervals(null, now)
        assertEquals(setOf(1, 2, 3, 4), intervals.keys)
        assertTrue(intervals.getValue(4) >= intervals.getValue(3))
        assertTrue(intervals.getValue(3) >= intervals.getValue(1))
        val next = engine.review(null, GRADE_GOOD, now)
        assertEquals(2.3065, next.stability!!, 1e-9)
        assertEquals(1, next.reps)
        assertEquals(0, next.lapses)
    }

    @Test
    fun forgettingCountsALapseAndStateSurvivesJson() {
        val engine = Fsrs()
        val t0 = Instant.parse("2026-09-20T10:00:00Z")
        val first = engine.review(null, GRADE_GOOD, t0)
        val restored = CardMemory.fromJson(first.toJson())
        assertEquals(first, restored)
        val lapsed = engine.review(restored, GRADE_AGAIN, t0.plus(Duration.ofDays(3)))
        assertEquals(1, lapsed.lapses)
        assertEquals(2, lapsed.reps)
        assertTrue(lapsed.stability!! < first.stability!! * 5)
        assertTrue(CardMemory.fromJson(null).isNew)
    }

    @Test
    fun sameDayReviewUsesShortTermRule() {
        val engine = Fsrs()
        val t0 = Instant.parse("2026-09-20T10:00:00Z")
        val first = engine.review(null, GRADE_GOOD, t0)
        val again = engine.review(first, GRADE_GOOD, t0.plus(Duration.ofHours(2)))
        assertTrue(again.stability!! >= first.stability!!)
        assertEquals(Fsrs.forRetention(0.99).retention, 0.99, 0.0)
        assertEquals(Fsrs.DEFAULT_RETENTION, Fsrs.forRetention(5.0).retention, 0.0)
    }

    @Test
    fun timeTextRoundTrips() {
        assertEquals("2026-09-20T10:00:00.000Z", FsrsTime.format(Instant.parse("2026-09-20T10:00:00Z")))
        assertEquals(Instant.parse("2026-09-20T10:00:00.120Z"), FsrsTime.parse("2026-09-20T15:30:00.12+05:30"))
        assertEquals(null, FsrsTime.parse("not a time"))
        assertEquals(null, FsrsTime.parse(null))
    }
}

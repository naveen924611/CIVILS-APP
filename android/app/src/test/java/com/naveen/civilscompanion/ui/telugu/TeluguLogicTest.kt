package com.naveen.civilscompanion.ui.telugu

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeluguLogicTest {
    private val monday = "2026-09-21"

    private fun items(vocab: Int = 30): List<PItem> =
        (1..vocab).map { PItem("v$it", "vocab", it) } +
            (1..3).map { PItem("p$it", "passage", it) } +
            (1..5).map { PItem("t$it", "translation", it) } +
            (1..2).map { PItem("w$it", "template", it) }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun quotasFollowTheMinutes() {
        assertEquals(mapOf("vocab" to 8, "passage" to 1, "translation" to 1, "template" to 0), TeluguPlan.quotas(15, 0))
        assertEquals(1, TeluguPlan.quotas(15, 2).getValue("template"))
        assertEquals(1, TeluguPlan.quotas(15, 5).getValue("template"))
        assertEquals(mapOf("vocab" to 10, "passage" to 1, "translation" to 2, "template" to 0), TeluguPlan.quotas(20, 0))
        assertEquals(mapOf("vocab" to 4, "passage" to 0, "translation" to 0, "template" to 0), TeluguPlan.quotas(5, 2))
        assertTrue(TeluguPlan.quotas(0, 2).values.all { it == 0 })
    }

    @Test
    fun firstDayIsFileOrder() {
        val ids = TeluguPlan.pickToday(items(), emptyList(), monday, 15).map { it.id }
        assertEquals((1..8).map { "v$it" } + listOf("p1", "t1"), ids)
    }

    @Test
    fun weakWordsComeBackButNewOnesStillFit() {
        val history = listOf(
            PDone("v1", 0.0, "2026-09-20", 1000),
            PDone("v2", 1.0, "2026-09-20", 2000),
            PDone("v3", null, "2026-09-20", 3000),
        )
        val ids = TeluguPlan.pickToday(items(), history, monday, 15).filter { it.kind == "vocab" }.map { it.id }
        assertEquals(listOf("v1", "v3", "v4", "v5", "v6", "v7", "v8", "v9"), ids)
    }

    @Test
    fun itemsDoneTodayStayInTodaysSet() {
        val history = listOf(PDone("v5", 1.0, monday, 5000))
        val ids = TeluguPlan.pickToday(items(), history, monday, 15).filter { it.kind == "vocab" }.map { it.id }
        assertEquals(listOf("v5", "v1", "v2", "v3", "v4", "v6", "v7", "v8"), ids)
    }

    @Test
    fun smallPoolPutsWellDoneItemsLast() {
        val pool = listOf(PItem("a", "vocab", 1), PItem("b", "vocab", 2))
        val history = listOf(PDone("a", 1.0, "2026-09-18", 100))
        assertEquals(listOf("b", "a"), TeluguPlan.pickToday(pool, history, monday, 15).map { it.id })
    }

    @Test
    fun streakCountsDaysInARow() {
        val days = setOf("2026-09-21", "2026-09-20", "2026-09-19", "2026-09-17")
        assertEquals(3, TeluguPlan.streak(days, monday))
        assertEquals(2, TeluguPlan.streak(days - "2026-09-21", monday))
        assertEquals(0, TeluguPlan.streak(emptySet(), monday))
    }

    @Test
    fun statsPerKindAndDay() {
        val pool = listOf(PItem("v1", "vocab", 1), PItem("v2", "vocab", 2), PItem("p1", "passage", 1))
        val history = listOf(
            PDone("v1", 1.0, "2026-09-21", 2000),
            PDone("v1", 0.0, "2026-09-20", 1000),
            PDone("v2", 0.5, "2026-09-20", 1500),
        )
        val s = TeluguPlan.stats(pool, history, monday)
        assertEquals(3, s.totalDone)
        assertEquals(2, s.streak)
        assertEquals(KindStat(items = 2, practised = 2, avgScore = 0.75), s.kinds.getValue("vocab"))
        assertEquals(KindStat(items = 1, practised = 0, avgScore = null), s.kinds.getValue("passage"))
        assertEquals(14, s.recent.size)
        assertEquals(DayCount("2026-09-21", 1), s.recent.last())
        assertEquals(DayCount("2026-09-20", 2), s.recent[12])
    }

    @Test
    fun timesAndMinutes() {
        val india = ZoneId.of("Asia/Kolkata")
        assertEquals("2026-09-21", TeluguPlan.dayOf("2026-09-20T20:00:00Z", india))
        assertNull(TeluguPlan.dayOf("not a time", india))
        assertNull(TeluguPlan.dayOf(null, india))
        assertEquals(0L, TeluguPlan.epochOf(""))
        assertEquals(25, TeluguPlan.parseMinutes(JsonPrimitive(25)))
        assertEquals(25, TeluguPlan.parseMinutes(JsonPrimitive(25.0)))
        assertEquals(120, TeluguPlan.parseMinutes(JsonPrimitive(500)))
        assertEquals(15, TeluguPlan.parseMinutes(JsonPrimitive("abc")))
        assertEquals(15, TeluguPlan.parseMinutes(JsonNull))
        assertEquals(15, TeluguPlan.parseMinutes(null))
    }

    @Test
    fun scores() {
        assertEquals(0.75, TeluguPlan.passageScore(3, 4), 0.0001)
        assertEquals(0.0, TeluguPlan.passageScore(0, 0), 0.0001)
        assertEquals(0.75, TeluguPlan.fractionOf10(7.5), 0.0001)
        assertEquals(1.0, TeluguPlan.fractionOf10(12.0), 0.0001)
        assertEquals("-", TeluguPlan.percent(null))
        assertEquals("76%", TeluguPlan.percent(0.756))
    }

    @Test
    fun contentIsReadForgivingly() {
        val v = TeluguContent.vocab(obj("""{"te":"నది","roman":"nadi","en":"river","ex_te":"గోదావరి నది.","ex_en":"The Godavari is a river."}"""))
        assertEquals("river", v.en)
        assertEquals("nadi", v.roman)
        val p = TeluguContent.passage(
            obj(
                """{"title":"T","title_en":"Time","text_te":"x","text_en":"y","questions":[
                {"q_te":"a","q_en":"a","options":["1","2","3","4"],"answer":2},
                {"q_te":"b","q_en":"b","options":["1","2"],"answer":9}]}""",
            ),
        )
        assertEquals(1, p.questions.size)
        assertEquals(2, p.questions[0].answer)
        val t = TeluguContent.translation(obj("""{"en":"Hi","reference":"హాయ్","hint_words":[{"te":"హాయ్","en":"hi"}]}"""))
        assertTrue(t.toTelugu)
        assertEquals(1, t.hints.size)
        assertTrue(!TeluguContent.translation(obj("""{"direction":"te_to_en","te":"x","reference":"y"}""")).toTelugu)
        val w = TeluguContent.template(obj("""{"title_en":"Letter","task_en":"Write","structure":[{"te":"a","en":"b"}]}"""))
        assertEquals(100, w.minWords)
        assertEquals(1, w.structure.size)
        assertEquals("", w.sampleTe)
        assertTrue(TeluguContent.isGeneral(obj("""{"official":false}""")))
        assertTrue(!TeluguContent.isGeneral(obj("""{"official":true}""")))
    }

    @Test
    fun feedbackIsReadFromTheJobResult() {
        val r = obj(
            """{"item_id":"x","score":7.5,"feedback":{"summary":"Nice.","strengths":["Meaning"],
            "corrections":[{"said":"a","better":"b","why":"c"}],"model_answer":"m"}}""",
        )
        val fb = TeluguContent.feedback(r)
        assertNotNull(fb)
        assertEquals(7.5, fb!!.score, 0.0001)
        assertEquals("b", fb.corrections[0].better)
        assertEquals(listOf("Meaning"), fb.strengths)
        assertEquals(10.0, TeluguContent.feedback(obj("""{"score":40,"feedback":{}}"""))!!.score, 0.0001)
        assertNull(TeluguContent.feedback(null))
        assertNull(TeluguContent.feedback(obj("""{"score":1}""")))
    }

    @Test
    fun wordCounter() {
        assertEquals(3, TeluguContent.wordCount("ఒకటి రెండు  మూడు"))
        assertEquals(0, TeluguContent.wordCount("   "))
    }
}

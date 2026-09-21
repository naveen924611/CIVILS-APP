package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.records.RecordJson
import com.naveen.civilscompanion.ui.report.Adjustments
import com.naveen.civilscompanion.ui.report.HoursData
import com.naveen.civilscompanion.ui.report.ReportData
import com.naveen.civilscompanion.ui.report.ReportLogic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLogicTest {
    private val sample = """
        {"week_start":"2026-09-14","week_end":"2026-09-20","narrative":"Good week.","script":"Your weekly report.",
         "hours":{"planned_minutes":200,"done_minutes":100,"focus_minutes":50,
                  "by_day":[{"date":"2026-09-14","day":"mon","planned":100,"done":0}]},
         "topics_finished":[{"topic_id":"t1","title":"Federalism"}],
         "covered":[{"topic_id":"t1","title":"Federalism","subject":"Polity"}],
         "cards":{"revised":2,"distinct":2,"fading":1},
         "mcq":{"attempted":4,"correct":2,"accuracy":0.5,"tests":[{"title":"Weekly mock","score":null,"total":25}]},
         "weak_spots":{"topics":[{"topic_id":"t1","title":"Federalism","strength":0.3}],"fading_cards":1,"low_days":["Monday"]},
         "next_week":{"week_start":"2026-09-21","changes":["Focus on: Federalism."],
                      "adjustments":{"week_start":"2026-09-21","extra_revision_minutes":15,"focus_topic_ids":["t1"],"reduce_new_topics":true}},
         "last_month":{"active":true,"exam":"UPSC CSE Prelims","days_left":12},
         "something_new":123}
    """.trimIndent()

    @Test
    fun parsesTheServerReport() {
        val d = ReportLogic.parse(RecordJson.parseToJsonElement(sample) as JsonObject)
        assertEquals("2026-09-14", d.weekStart)
        assertEquals(100, d.hours.doneMinutes)
        assertEquals("mon", d.hours.byDay[0].day)
        assertEquals("Polity", d.covered[0].subject)
        assertEquals(0.5, d.mcq.accuracy, 0.0001)
        assertNull(d.mcq.tests[0].score)
        assertEquals(0.3, d.weakSpots.topics[0].strength, 0.0001)
        assertEquals(15, d.nextWeek.adjustments.extraRevisionMinutes)
        assertTrue(d.nextWeek.adjustments.reduceNewTopics)
        assertEquals(12, d.lastMonth.daysLeft)
    }

    @Test
    fun anEmptyOrBrokenReportGivesDefaults() {
        assertEquals(ReportData(), ReportLogic.parse(JsonObject(emptyMap())))
        val broken = JsonObject(mapOf("hours" to JsonPrimitive("oops")))
        assertEquals(ReportData(), ReportLogic.parse(broken))
    }

    @Test
    fun percentAndTimeText() {
        assertEquals(50, ReportLogic.donePercent(HoursData(plannedMinutes = 200, doneMinutes = 100)))
        assertEquals(0, ReportLogic.donePercent(HoursData()))
        assertEquals(100, ReportLogic.donePercent(HoursData(plannedMinutes = 100, doneMinutes = 150)))
        assertEquals("0 min", ReportLogic.hoursText(0))
        assertEquals("45 min", ReportLogic.hoursText(45))
        assertEquals("1 h", ReportLogic.hoursText(60))
        assertEquals("1 h 35 min", ReportLogic.hoursText(95))
        assertEquals("Mon", ReportLogic.dayLabel("mon"))
    }

    @Test
    fun mondayOfTheWeek() {
        assertEquals("2026-09-14", ReportLogic.mondayOf("2026-09-20"))
        assertEquals("2026-09-14", ReportLogic.mondayOf("2026-09-14"))
        assertEquals("2026-09-14", ReportLogic.mondayOf("2026-09-16"))
        assertEquals("2026-09-21", ReportLogic.mondayOf("2026-09-21"))
    }

    @Test
    fun acceptedPlanIsWrittenInTheAgreedShape() {
        val a = Adjustments(weekStart = "2026-09-21", extraRevisionMinutes = 30, focusTopicIds = listOf("t1", "t2"), reduceNewTopics = true)
        val json = ReportLogic.adjustmentsJson(a, "2026-09-21")
        assertEquals("2026-09-21", json.getValue("week_start").jsonPrimitive.content)
        assertEquals(30, json.getValue("extra_revision_minutes").jsonPrimitive.int)
        assertEquals(2, json.getValue("focus_topic_ids").jsonArray.size)
        assertEquals("true", json.getValue("reduce_new_topics").jsonPrimitive.content)
        assertEquals("2026-09-28", ReportLogic.adjustmentsJson(Adjustments(), "2026-09-28").getValue("week_start").jsonPrimitive.content)
        assertTrue(ReportLogic.isAccepted(json, "2026-09-21"))
        assertFalse(ReportLogic.isAccepted(json, "2026-09-28"))
        assertFalse(ReportLogic.isAccepted(null, "2026-09-21"))
        assertFalse(ReportLogic.isAccepted(JsonPrimitive("x"), "2026-09-21"))
        assertFalse(ReportLogic.isAccepted(JsonArray(emptyList()), "2026-09-21"))
    }

    @Test
    fun weekTitles() {
        assertEquals("14 Sep to 20 Sep", ReportLogic.weekTitle("2026-09-14", "2026-09-20"))
        assertEquals("14 Sep", ReportLogic.weekTitle("2026-09-14", ""))
        assertEquals("bad", ReportLogic.weekTitle("bad", ""))
    }
}

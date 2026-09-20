package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.RecordJson
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.ui.common.MdBlock
import com.naveen.civilscompanion.ui.common.parseInline
import com.naveen.civilscompanion.ui.common.parseMarkdown
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationTest {
    private val serverTopic = """
        {"id":"t1","parent_id":null,"syllabus_id":"s1","title":"Fundamental Rights","level":2,"paper":"GS2",
         "exam_tags":["UPSC","APPSC"],"est_hours":3.5,"status":"in_progress","importance":7.5,"strength":0.4,
         "approved":true,"position":4,"updated_at":"2026-09-20T05:30:00.123456Z","deleted":false,"future_field":1}
    """.trimIndent()

    @Test
    fun serverRowsDecodeAndUnknownFieldsAreIgnored() {
        val topic = RecordJson.decodeFromString(Topic.serializer(), serverTopic)
        assertEquals("Fundamental Rights", topic.title)
        assertNull(topic.parentId)
        assertEquals(listOf("UPSC", "APPSC"), topic.examTags)
        assertEquals(3.5, topic.estHours, 0.0)
    }

    @Test
    fun nullsForPlainFieldsFallBackToDefaults() {
        val card = RecordJson.decodeFromString(Card.serializer(), """{"id":"c","front":null,"back":"B","group":null}""")
        assertEquals("", card.front)
        assertEquals("Current affairs", card.group)
    }

    @Test
    fun tableIndexReadsTextNumbersAndDates() {
        val obj = RecordJson.parseToJsonElement(serverTopic).jsonObject
        val idx = Tables.Topics.index(obj)
        assertNull(idx.k1)
        assertEquals("in_progress", idx.k2)
        assertEquals(4.0, idx.n1!!, 0.0)
        assertTrue(idx.text.contains("Fundamental Rights") && idx.text.contains("GS2"))
        val card = RecordJson.parseToJsonElement("""{"id":"c","front":"Q","back":"A","due_at":"2026-09-21T00:00:00Z","source_id":"n1"}""").jsonObject
        val cardIdx = Tables.Cards.index(card)
        assertEquals(TimeUtil.parse("2026-09-21T00:00:00Z")!!.toDouble(), cardIdx.n1!!, 0.0)
        assertEquals("n1", cardIdx.k2)
    }

    @Test
    fun booleanKeysBecomeOneOrZero() {
        val obj = RecordJson.parseToJsonElement("""{"id":"r","type":"slot","enabled":true}""").jsonObject
        assertEquals(1.0, Tables.RevisionRules.index(obj).n1!!, 0.0)
    }

    @Test
    fun everyTableHasAUniqueName() {
        assertEquals(31, Tables.all.size)
    }

    @Test
    fun searchTextIsEscapedForLike() {
        assertEquals("%50\\% of \\_x%", RecordQuery(contains = " 50% of _x ").like())
        assertNull(RecordQuery(contains = "  ").like())
    }

    @Test
    fun encodedModelKeepsServerNames() {
        val json = RecordJson.encodeToJsonElement(Topic.serializer(), Topic(id = "x", title = "T", parentId = "p")) as JsonObject
        assertEquals("\"p\"", json["parent_id"].toString())
        assertTrue(json.containsKey("est_hours"))
    }

    @Test
    fun markdownBlocks() {
        val md = "# Title\n\nSome **bold** text\nsecond line\n\n- one\n  - nested\n1. first\n> quote\n---\n```\ncode\n```\n| a | b |\n| 1 | 2 |"
        val blocks = parseMarkdown(md)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Para("Some **bold** text second line"), blocks[1])
        assertEquals(MdBlock.Bullet("one", 0), blocks[2])
        assertEquals(MdBlock.Bullet("nested", 1), blocks[3])
        assertEquals(MdBlock.Numbered(1, "first"), blocks[4])
        assertEquals(MdBlock.Quote("quote"), blocks[5])
        assertEquals(MdBlock.Rule, blocks[6])
        assertEquals(MdBlock.Code("code"), blocks[7])
        assertEquals(MdBlock.Code("| a | b |\n| 1 | 2 |"), blocks[8])
    }

    @Test
    fun markdownInline() {
        val spans = parseInline("a **b** *c* `d` [e](http://x) 5 * 3")
        assertEquals("a ", spans[0].text)
        assertTrue(spans[1].bold && spans[1].text == "b")
        assertTrue(spans[3].italic && spans[3].text == "c")
        assertTrue(spans[5].code && spans[5].text == "d")
        assertEquals("a b c d e 5 * 3", spans.joinToString("") { it.text })
    }

    @Test
    fun dayHelpersUseIndiaTime() {
        // 2026-09-20 20:00 UTC is already 21 September in India (01:30)
        val ms = TimeUtil.parse("2026-09-20T20:00:00Z")!!
        assertEquals("2026-09-21", TimeUtil.dateOf(ms))
        assertEquals(TimeUtil.parse("2026-09-20T18:30:00Z"), TimeUtil.startOfDay("2026-09-21"))
    }
}

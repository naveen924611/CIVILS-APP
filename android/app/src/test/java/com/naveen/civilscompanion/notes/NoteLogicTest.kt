package com.naveen.civilscompanion.notes

import com.naveen.civilscompanion.ui.notes.NoteLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLogicTest {
    private val mine = "## My notes\n\nArticle 21 is life and liberty."
    private val withBlock = mine + "\n\n" + NoteLogic.SUGGESTED_HEADING + "\n\n" + NoteLogic.SUGGESTED_INTRO +
        "\n\n- Article 14 is equality\n- Must remember: Article 19 is freedom\n"

    @Test
    fun splitReadsTheBlockAndKeepsTheOwnersText() {
        val parts = NoteLogic.split(withBlock)
        assertEquals(mine, parts.body)
        assertEquals(listOf("Article 14 is equality", "Must remember: Article 19 is freedom"), parts.suggestions)
        val plain = NoteLogic.split(mine)
        assertEquals(mine, plain.body)
        assertTrue(plain.suggestions.isEmpty())
    }

    @Test
    fun splitKeepsHeadingsThatComeAfterTheBlock() {
        val text = mine + "\n\n" + NoteLogic.SUGGESTED_HEADING + "\n\n- one\n\n## Later\n\nText after."
        val parts = NoteLogic.split(text)
        assertEquals(listOf("one"), parts.suggestions)
        assertTrue(parts.body.contains("## Later"))
        assertTrue(parts.body.startsWith(mine))
    }

    @Test
    fun acceptMovesOneItemIntoTheOwnersText() {
        val out = NoteLogic.accept(withBlock, "Article 14 is equality")
        val parts = NoteLogic.split(out)
        assertTrue(parts.body.startsWith(mine))
        assertTrue(parts.body.endsWith("- Article 14 is equality"))
        assertEquals(listOf("Must remember: Article 19 is freedom"), parts.suggestions)
    }

    @Test
    fun acceptingMustRememberWritesBoldLabel() {
        val out = NoteLogic.accept(withBlock, "Must remember: Article 19 is freedom")
        assertTrue(out.contains("- **Must remember:** Article 19 is freedom"))
    }

    @Test
    fun acceptAllEmptiesTheBlock() {
        val out = NoteLogic.acceptAll(withBlock)
        assertFalse(out.contains(NoteLogic.SUGGESTED_HEADING))
        assertTrue(out.contains("- Article 14 is equality"))
        assertTrue(out.contains("- **Must remember:** Article 19 is freedom"))
        assertTrue(out.startsWith(mine))
    }

    @Test
    fun dismissRemovesOnlyTheBlockItems() {
        val one = NoteLogic.dismiss(withBlock, "Article 14 is equality")
        assertEquals(listOf("Must remember: Article 19 is freedom"), NoteLogic.split(one).suggestions)
        assertEquals(mine, NoteLogic.split(one).body)
        val none = NoteLogic.dismissAll(withBlock)
        assertEquals(mine + "\n", none)
    }

    @Test
    fun appendingToAListKeepsItTogether() {
        val list = "Points\n\n- first\n- second"
        val out = NoteLogic.accept(list + "\n\n" + NoteLogic.SUGGESTED_HEADING + "\n\n- third", "third")
        assertTrue(out.startsWith("Points\n\n- first\n- second\n- third"))
    }

    @Test
    fun sectionsAreReadDefensively() {
        val json = Json.parseToJsonElement(
            """{"must_remember":["A"," ",7],"in_the_news":[{"id":"n1","title":"RBI holds rate","summary":"s","url":"https://x","source":"PIB","published_at":"2026-09-18T07:00:00Z"},{"title":""},"junk"]}""",
        ).jsonObject
        assertEquals(listOf("A", "7"), NoteLogic.mustRemember(json))
        val news = NoteLogic.news(json)
        assertEquals(1, news.size)
        assertEquals("RBI holds rate", news[0].title)
        assertEquals("18 Sep", NoteLogic.shortDate(news[0].publishedAt))
        assertTrue(NoteLogic.news(JsonObject(emptyMap())).isEmpty())
    }

    @Test
    fun sourcesAreCleanedAndDeduplicated() {
        val list = Json.parseToJsonElement(
            """[{"document_id":"d1","title":"Laxmikanth","page":7},{"document_id":"d1","title":"Laxmikanth","page":7},{"document_id":"d2","title":""},{}]""",
        ).let { (it as kotlinx.serialization.json.JsonArray).map { e -> e as JsonObject } }
        val out = NoteLogic.sources(list)
        assertEquals(2, out.size)
        assertEquals("Laxmikanth, page 7", NoteLogic.sourceLine(out[0]))
        assertEquals(1, out[1].page)
    }

    @Test
    fun nextRevisionIsInWords() {
        val now = 1_000_000_000L
        val day = 86_400_000L
        assertEquals("No cards yet", NoteLogic.nextRevision(emptyList(), now))
        assertEquals("Due now", NoteLogic.nextRevision(listOf(now - 5), now))
        assertEquals("Later today", NoteLogic.nextRevision(listOf(now + 1000), now))
        assertEquals("Tomorrow", NoteLogic.nextRevision(listOf(now + day + 5), now))
        assertEquals("In 3 days", NoteLogic.nextRevision(listOf(now + 3 * day + 5, now + 9 * day), now))
    }

    @Test
    fun smallTextHelpers() {
        assertEquals(3, NoteLogic.wordCount("  one two\nthree "))
        assertEquals("nonsense", NoteLogic.shortDate("nonsense"))
        assertTrue(NoteLogic.isBlankNote(""))
        assertTrue(NoteLogic.isBlankNote(NoteLogic.NO_MATERIAL_TEXT))
        assertFalse(NoteLogic.isBlankNote("Something"))
        val speech = NoteLogic.sentencesForSpeech("## Overview\n\nFederalism divides power. It has lists.\n\n- Point one\n- Point two")
        assertEquals(listOf("Overview", "Federalism divides power.", "It has lists.", "Point one", "Point two"), speech)
    }

    @Test
    fun snippetShowsTheWordsInContext() {
        val text = "Long line about Indian federalism and the Sarkaria Commission that reported in 1988 on Centre-State relations."
        val out = NoteLogic.snippet(text, "sarkaria", radius = 10)
        assertTrue(out.contains("Sarkaria"))
        assertTrue(out.startsWith("...") && out.endsWith("..."))
        assertEquals("", NoteLogic.snippet(text, "astronomy"))
        assertEquals("", NoteLogic.snippet(text, "   "))
    }
}

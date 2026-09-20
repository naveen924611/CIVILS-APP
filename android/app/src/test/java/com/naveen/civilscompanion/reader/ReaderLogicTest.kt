package com.naveen.civilscompanion.reader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {
    private fun texts(s: String) = SentenceSplitter.split(s).map { it.text }

    @Test
    fun splitsOnFullStopsQuestionsAndExclamations() {
        assertEquals(
            listOf("Article 21 protects life.", "It is important!", "Is it?", "Yes."),
            texts("Article 21 protects life. It is important! Is it? Yes."),
        )
    }

    @Test
    fun doesNotSplitAfterShortFormsInitialsOrDecimals() {
        assertEquals(2, texts("See Art. 21 of the Constitution. It applies to everyone.").size)
        assertEquals(1, texts("M. Laxmikanth wrote Indian Polity.").size)
        assertEquals(listOf("Growth was 3.5 per cent.", "Next year it rose."), texts("Growth was 3.5 per cent. Next year it rose."))
        assertEquals(1, texts("It ended. and then it went on.").size)
    }

    @Test
    fun blankLineEndsASentenceAndClosingQuotesStayWithIt() {
        assertEquals(listOf("First paragraph without a stop", "Second paragraph."), texts("First paragraph without a stop\n\nSecond paragraph."))
        assertEquals(listOf("He said \"stop.\"", "Then left."), texts("He said \"stop.\" Then left."))
    }

    @Test
    fun handlesTeluguDandaAndEmptyText() {
        assertEquals(2, texts("ఇది మొదటి వాక్యం। ఇది రెండవది।").size)
        assertTrue(SentenceSplitter.split("   \n  ").isEmpty())
    }

    @Test
    fun offsetsMatchTheOriginalText() {
        val text = "Alpha beta.  Gamma delta!\n\n  Epsilon zeta. Eta"
        val list = SentenceSplitter.split(text)
        assertEquals(4, list.size)
        for (s in list) assertEquals(s.text, text.substring(s.start, s.end))
        assertEquals(0, SentenceSplitter.indexAt(list, 3))
        assertEquals(1, SentenceSplitter.indexAt(list, list[1].start + 2))
        assertEquals(3, SentenceSplitter.indexAt(list, 999))
        assertEquals(-1, SentenceSplitter.indexAt(emptyList(), 0))
    }

    @Test
    fun longSentencesAreCut() {
        val text = "word ".repeat(300).trim() + "."
        val list = SentenceSplitter.split(text)
        assertTrue(list.size >= 3)
        assertTrue(list.all { it.text.length <= SentenceSplitter.MAX_LENGTH })
        for (s in list) assertEquals(s.text, text.substring(s.start, s.end))
    }

    @Test
    fun spotsArticlesAmendmentsDatesNumbersAndBodies() {
        val text = "Article 21 protects life. The 42nd Amendment Act, 1976 added the word Socialist on 3 January 1977. " +
            "The Sarkaria Commission reported. Growth was 7.5 per cent in 2023. Article 21 again."
        val facts = FactSpotter.spot(text)
        fun of(kind: FactKind) = facts.filter { it.kind == kind }.map { it.text }
        assertEquals(listOf("Article 21"), of(FactKind.Article))
        assertEquals(listOf("42nd Amendment Act, 1976"), of(FactKind.Amendment))
        assertEquals(listOf("3 January 1977", "2023"), of(FactKind.Date))
        assertEquals(listOf("7.5 per cent"), of(FactKind.Number))
        assertEquals(listOf("Sarkaria Commission"), of(FactKind.Body))
        assertEquals("Article 21 protects life.", facts.first { it.kind == FactKind.Article }.sentence)
    }

    @Test
    fun articleFormsAndEmptyInput() {
        val facts = FactSpotter.spot("Art. 370 was changed. Articles 14 and Article 21A matter. Art 32(2) too.")
        assertEquals(listOf("Art. 370", "Articles 14", "Article 21A", "Art 32(2)"), facts.filter { it.kind == FactKind.Article }.map { it.text })
        assertTrue(FactSpotter.spot("  ").isEmpty())
        assertTrue(FactSpotter.spot("Nothing to spot in this line at all.").isEmpty())
    }

    @Test
    fun readingPositionRoundTrips() {
        val json = ReadingPosition(7, 12).toJson()
        assertEquals(ReadingPosition(7, 12), ReadingPosition.from(json))
        assertEquals(ReadingPosition(1, 0), ReadingPosition.from(JsonObject(emptyMap())))
        assertEquals(ReadingPosition(1, 0), ReadingPosition.from(null))
        assertEquals(ReadingPosition(1, 0), ReadingPosition.from(JsonObject(mapOf("page" to JsonPrimitive(-4), "sentence" to JsonPrimitive("x")))))
        assertEquals(50, readingPercent(5, 10))
        assertEquals(100, readingPercent(50, 10))
        assertEquals(0, readingPercent(3, 0))
    }

    @Test
    fun statusLooksAreKindAndSimple() {
        assertEquals(StatusLook("Processed · searchable", 1, false), statusLook("processed", ""))
        assertEquals(StatusLook("Converting page 3 of 12", 2, true), statusLook("processing", "Converting page 3 of 12"))
        assertEquals(StatusLook("Waiting for internet (Telugu page)", 2, true), statusLook("waiting", "Waiting for internet (Telugu page)"))
        assertEquals(3, statusLook("failed", "").tone)
        assertEquals("Needs OCR", statusLook("needs_ocr", "").label)
        assertEquals("Photos", typeLabel("image"))
    }

    @Test
    fun groupsBySubjectWithUnsortedLast() {
        val docs = listOf("a" to "", "b" to "Polity", "c" to "Economy", "d" to "Polity")
        val groups = groupBySubject(docs) { it.second }
        assertEquals(listOf("Economy", "Polity", NO_SUBJECT), groups.map { it.first })
        assertEquals(listOf("b", "d"), groups[1].second.map { it.first })
    }

    @Test
    fun searchHelpers() {
        assertTrue(titleMatches("Laxmikanth Indian Polity", "polity laxmi"))
        assertFalse(titleMatches("Laxmikanth Indian Polity", "economy"))
        assertTrue(titleMatches("Anything", "  "))
        val snippet = snippetAround("The right to life is in Article 21 of the Constitution of India and it is wide.", "article", 10)
        assertTrue(snippet.contains("Article"))
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
        assertEquals("short text", snippetAround("short   text", "zzz"))
        assertTrue(looksTelugu("భారత రాజ్యాంగం ప్రజలకు"))
        assertFalse(looksTelugu("Article 21 and ఒక"))
        assertTrue(askAboutPageText("A  b\nc", 4, "Book").contains("page 4"))
    }

    @Test
    fun paragraphsFollowBlankLines() {
        val text = "First para\nstill first.\n\n  Second para.\n \n\nThird."
        val list = paragraphsOf(text)
        assertEquals(listOf("First para\nstill first.", "Second para.", "Third."), list.map { text.substring(it.start, it.end) })
        assertEquals(1, paragraphIndexAt(list, list[1].start + 1))
        assertEquals(2, paragraphIndexAt(list, 9999))
        assertEquals(-1, paragraphIndexAt(emptyList(), 0))
        assertTrue(paragraphsOf("   ").isEmpty())
    }
}

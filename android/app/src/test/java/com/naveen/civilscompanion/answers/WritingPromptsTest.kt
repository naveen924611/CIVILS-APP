package com.naveen.civilscompanion.answers

import com.naveen.civilscompanion.ui.answers.WritingPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingPromptsTest {
    private val sample = """
        {"version":1,"note":"n","extra":true,"items":[
          {"key":"a","exam":"SI","language":"en","paper":"P1","form":"essay","marks":15,"word_limit":200,"prompt":"Write about roads.\n\nTopic: roads","source":"general"},
          {"key":"b","exam":"APPSC","language":"te","paper":"P2","form":"press_release","marks":10,"word_limit":100,"prompt":"Telugu prompt","source":"general","unknown":1},
          {"key":"c","exam":"BOTH","language":"en","paper":"P3","form":"letter","marks":10,"word_limit":150,"prompt":"Write a letter","source":"general"},
          {"key":"","exam":"SI","language":"en","prompt":"no key"},
          {"key":"d","exam":"SI","language":"en","prompt":"   "}
        ]}
    """.trimIndent()

    @Test
    fun parseReadsItemsAndDropsBrokenOnes() {
        val list = WritingPrompts.parse(sample)
        assertEquals(listOf("a", "b", "c"), list.map { it.key })
        assertEquals(200, list[0].wordLimit)
        assertEquals("te", list[1].language)
        assertEquals("BOTH", list[2].exam)
    }

    @Test
    fun parseIsForgivingAboutBadInput() {
        assertTrue(WritingPrompts.parse("not json").isEmpty())
        assertTrue(WritingPrompts.parse("").isEmpty())
        assertTrue(WritingPrompts.parse("{\"items\":[]}").isEmpty())
    }

    @Test
    fun filterByExamAndLanguage() {
        val list = WritingPrompts.parse(sample)
        assertEquals(listOf("a", "c"), WritingPrompts.filter(list, null, "en").map { it.key })
        assertEquals(listOf("a", "c"), WritingPrompts.filter(list, "SI", "en").map { it.key })
        assertEquals(listOf("c"), WritingPrompts.filter(list, "APPSC", "en").map { it.key })
        assertEquals(listOf("b"), WritingPrompts.filter(list, "APPSC", "te").map { it.key })
        assertTrue(WritingPrompts.filter(list, "SI", "te").isEmpty())
    }

    @Test
    fun labelsAndPreview() {
        val list = WritingPrompts.parse(sample)
        assertEquals("Essay, 200 words", WritingPrompts.rowLabel(list[0]))
        assertEquals("Press release, 100 words", WritingPrompts.rowLabel(list[1]))
        assertEquals("Write about roads. Topic: roads", WritingPrompts.preview(list[0]))
        assertEquals("Write...", WritingPrompts.preview(list[0], 5))
    }
}

package com.naveen.civilscompanion.ui.videos

import com.naveen.civilscompanion.data.model.Video
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLogicTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun acceptsEveryKindOfLink() {
        listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "https://www.youtube.com/watch?v=$id&t=42s&list=PL1",
            "http://youtube.com/watch?feature=share&v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc",
            "youtu.be/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "  https://music.youtube.com/watch?v=$id  ",
        ).forEach { assertEquals(it, id, VideoLogic.parseYoutubeId(it)) }
    }

    @Test fun rejectsEverythingElse() {
        listOf(
            null, "", "hello", "short", "https://example.com/watch?v=$id", "https://www.youtube.com/watch?v=tooshort",
            "https://www.youtube.com/", "https://www.youtube.com/channel/UC12345678901", "https://youtu.be/",
            "https://evil.com/youtu.be/$id", "not a url at all!!", "ftp://[bad",
        ).forEach { assertNull(it, VideoLogic.parseYoutubeId(it)) }
    }

    @Test fun timeText() {
        assertEquals("0:00", VideoLogic.formatTime(0))
        assertEquals("1:15", VideoLogic.formatTime(75))
        assertEquals("1:02:05", VideoLogic.formatTime(3725))
        assertEquals("0:00", VideoLogic.formatTime(-4))
        assertEquals(75, VideoLogic.parseTime("1:15"))
        assertEquals(75, VideoLogic.parseTime("01:15"))
        assertEquals(3725, VideoLogic.parseTime("1:02:05"))
        assertEquals(75, VideoLogic.parseTime("75"))
        assertNull(VideoLogic.parseTime("a"))
        assertNull(VideoLogic.parseTime(""))
        assertNull(VideoLogic.parseTime("1:2:3:4"))
        assertNull(VideoLogic.parseTime("-1"))
    }

    @Test fun durationsAndLinks() {
        assertEquals("", VideoLogic.durationLabel(0))
        assertEquals("1 min", VideoLogic.durationLabel(20))
        assertEquals("10 min", VideoLogic.durationLabel(600))
        assertEquals("1 h 5 min", VideoLogic.durationLabel(3900))
        assertEquals("https://www.youtube.com/watch?v=$id", VideoLogic.watchUrl(id))
        assertEquals("https://www.youtube.com/watch?v=$id&t=95s", VideoLogic.watchUrl(id, 95))
    }

    @Test fun playerErrors() {
        assertTrue(VideoLogic.embeddingBlocked(101))
        assertTrue(VideoLogic.embeddingBlocked(150))
        assertTrue(VideoLogic.embeddingBlocked(153))
        assertFalse(VideoLogic.embeddingBlocked(100))
        assertTrue(VideoLogic.errorText(100).contains("removed"))
        assertTrue(VideoLogic.errorText(77).contains("77"))
    }

    @Test fun playerPageOnlyForRealIds() {
        assertEquals("", VideoLogic.embedHtml("bad id'; alert(1)"))
        val html = VideoLogic.embedHtml(id)
        assertTrue(html.contains("videoId:'$id'"))
        assertTrue(html.contains("Android.onError"))
        assertTrue(html.contains("function seekTo"))
    }

    @Test fun sortingAndFilters() {
        val a = Video(id = "a", title = "B", watched = true)
        val b = Video(id = "b", title = "A")
        val c = Video(id = "c", title = "C")
        assertEquals(listOf("b", "c", "a"), VideoLogic.sorted(listOf(a, c, b)).map { it.id })
        assertEquals(listOf("b", "c"), VideoLogic.filtered(listOf(a, b, c), "todo").map { it.id })
        assertEquals(listOf("a"), VideoLogic.filtered(listOf(a, b, c), "watched").map { it.id })
        assertEquals(3, VideoLogic.filtered(listOf(a, b, c), "all").size)
    }

    @Test fun serverAnswersAndSummary() {
        assertEquals("Nope", VideoLogic.detailOf("{\"detail\":\"Nope\"}"))
        assertNull(VideoLogic.detailOf("oops"))
        assertNull(VideoLogic.detailOf(null))
        assertNull(VideoLogic.detailOf("{\"detail\":\" \"}"))
        val summary = JsonObject(mapOf("text" to JsonPrimitive(" Hello "), "note" to JsonPrimitive("Check it")))
        assertEquals("Hello" to "Check it", VideoLogic.summaryOf(summary))
        assertNull(VideoLogic.summaryOf(JsonObject(mapOf("text" to JsonPrimitive("")))))
        assertNull(VideoLogic.summaryOf(null))
        assertEquals("video.summary.abc-def", VideoLogic.summaryKey("ABC-DEF"))
    }
}

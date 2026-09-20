package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.model.ChatMessage
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.ui.ask.AskBubbles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskBubblesTest {
    private fun user(id: String, text: String, at: String, jobId: String? = null) =
        ChatMessage(id = id, conversationId = "c1", role = "user", content = text, jobId = jobId, at = at, via = "voice")

    private fun docSource() = JsonObject(
        mapOf("document_id" to JsonPrimitive("d1"), "title" to JsonPrimitive("Laxmikanth"), "page" to JsonPrimitive(12)),
    )

    @Test
    fun documentAndNoteSourcesBecomeLabelsAndRoutes() {
        val doc = AskBubbles.sourceRef(docSource())
        assertEquals("Laxmikanth, page 12", doc?.label)
        assertEquals("read/d1", doc?.route)
        val note = AskBubbles.sourceRef(
            JsonObject(mapOf("note_id" to JsonPrimitive("n1"), "topic" to JsonPrimitive("Polity"), "topic_id" to JsonPrimitive("t1"))),
        )
        assertEquals("Your notes: Polity", note?.label)
        assertEquals("notes/t1", note?.route)
        assertNull(AskBubbles.sourceRef(JsonObject(emptyMap())))
    }

    @Test
    fun waitingQuestionShowsItsStatus() {
        val msgs = listOf(user("m1", "What is FRBM?", "2026-09-20T10:00:00Z", "j1"))
        val queued = Job(id = "j1", type = "tutor_question", status = "queued")
        assertEquals("Waiting for internet", AskBubbles.build(msgs, listOf(queued)).single().status)
        assertEquals("The tutor is answering", AskBubbles.build(msgs, listOf(queued.copy(status = "running"))).single().status)
        val failed = AskBubbles.build(msgs, listOf(queued.copy(status = "failed", error = "Busy"))).single()
        assertTrue(failed.failed)
        assertEquals("Busy", failed.status)
        assertEquals("Waiting for internet", AskBubbles.build(msgs, emptyList()).single().status)
    }

    @Test
    fun finishedJobShowsAnswerBeforeTheMessageHasSynced() {
        val msgs = listOf(user("m1", "What is FRBM?", "2026-09-20T10:00:00Z", "j1"))
        val result = JsonObject(
            mapOf(
                "answer" to JsonPrimitive("It limits the deficit [1]."),
                "sources" to JsonArray(listOf(docSource())),
            ),
        )
        val job = Job(id = "j1", type = "tutor_question", status = "done", result = result, updatedAt = "2026-09-20T10:01:00Z")
        val bubbles = AskBubbles.build(msgs, listOf(job))
        assertEquals(2, bubbles.size)
        assertEquals("", bubbles[0].status)
        assertFalse(bubbles[1].fromUser)
        assertEquals("What is FRBM?", bubbles[1].question)
        assertEquals("Laxmikanth, page 12", bubbles[1].sources.single().label)
    }

    @Test
    fun syncedAnswerIsNotShownTwice() {
        val msgs = listOf(
            user("m1", "What is FRBM?", "2026-09-20T10:00:00Z", "j1"),
            ChatMessage(
                id = "m2", conversationId = "c1", role = "assistant", content = "It limits the deficit.",
                jobId = "j1", at = "2026-09-20T10:01:00Z", sources = listOf(docSource()),
            ),
        )
        val job = Job(id = "j1", type = "tutor_question", status = "done", result = JsonObject(mapOf("answer" to JsonPrimitive("It limits the deficit."))))
        val bubbles = AskBubbles.build(msgs, listOf(job))
        assertEquals(listOf("m1", "m2"), bubbles.map { it.key })
        assertEquals("What is FRBM?", bubbles[1].question)
        assertEquals("", bubbles[0].status)
    }

    @Test
    fun offlineAnswersAreMarkedAndOrderedByTime() {
        val offlineSource = JsonObject(
            mapOf("note_id" to JsonPrimitive("n1"), "topic" to JsonPrimitive("Polity"), "topic_id" to JsonPrimitive("t1"), "offline" to JsonPrimitive(true)),
        )
        val msgs = listOf(
            ChatMessage(id = "m2", conversationId = "c1", role = "assistant", content = "Paragraph", sources = listOf(offlineSource), at = "2026-09-20T10:00:00.001Z"),
            user("m1", "What is federalism?", "2026-09-20T10:00:00Z"),
        )
        val bubbles = AskBubbles.build(msgs, emptyList())
        assertEquals(listOf("m1", "m2"), bubbles.map { it.key })
        assertTrue(bubbles[1].offline)
        assertEquals("t1", bubbles[1].topicId)
        assertEquals("What is federalism?", bubbles[1].question)
    }

    @Test
    fun waitingPanelListsQuestionsWithStatus() {
        val jobs = listOf(
            Job(id = "a", type = "tutor_question", status = "queued", payload = jobPayload("question" to "Q1", "via" to "voice"), createdAt = "2026-09-20T10:00:00Z"),
            Job(id = "b", type = "tutor_question", status = "done", payload = jobPayload("question" to "Q2", "via" to "text"), createdAt = "2026-09-20T11:00:00Z"),
            Job(id = "c", type = "note_merge", status = "queued", createdAt = "2026-09-20T12:00:00Z"),
        )
        val rows = AskBubbles.waiting(jobs)
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertEquals("Answered", rows[0].status)
        assertEquals("Voice", rows[1].how)
        assertEquals("Queued", rows[1].status)
        assertEquals(1, AskBubbles.waitingCount(jobs))
    }
}

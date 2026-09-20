package com.naveen.civilscompanion.ui.ask

import com.naveen.civilscompanion.data.model.ChatMessage
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.ui.nav.Routes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Small readers for JSON objects that come from the server (text, whole number, yes/no, list of texts). */
fun JsonObject.jstr(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

fun JsonObject.jint(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.jnum(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.jbool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun JsonObject.jlist(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()

fun JsonObject.jobj(key: String): JsonObject? = this[key] as? JsonObject

/** Where an answer came from: a label and (when possible) the screen that opens it. */
data class SourceRef(val label: String, val route: String?)

/** One chat bubble as the screen draws it. */
data class Bubble(
    val key: String,
    val fromUser: Boolean,
    val text: String,
    val time: String,
    val via: String = "text",
    val offline: Boolean = false,
    val topicTitle: String = "",
    val topicId: String? = null,
    val sources: List<SourceRef> = emptyList(),
    /** For a question that has no answer yet: what is happening to it. */
    val status: String = "",
    val failed: Boolean = false,
    /** For an answer: the question it answers. */
    val question: String = "",
)

/** One row of the "Waiting for internet" panel. tone: 1 good, 2 waiting, 3 problem. */
data class WaitingRow(val id: String, val question: String, val time: String, val how: String, val status: String, val tone: Int)

object AskBubbles {
    fun sourceRef(o: JsonObject): SourceRef? {
        val docId = o.jstr("document_id")
        if (docId.isNotEmpty()) {
            val page = o.jint("page")
            val title = o.jstr("title").ifEmpty { "Document" }
            return SourceRef(if (page != null && page > 0) "$title, page $page" else title, Routes.readDoc(docId))
        }
        val topic = o.jstr("topic")
        if (o.jstr("note_id").isNotEmpty() || topic.isNotEmpty()) {
            val topicId = o.jstr("topic_id")
            return SourceRef("Your notes: " + topic.ifEmpty { "topic" }, if (topicId.isNotEmpty()) Routes.noteTopic(topicId) else null)
        }
        return null
    }

    fun sourcesOf(list: List<JsonObject>): List<SourceRef> = list.mapNotNull { sourceRef(it) }.distinct()

    private fun isOffline(list: List<JsonObject>): Boolean = list.any { it.jbool("offline") }

    private fun topicOf(list: List<JsonObject>): Pair<String?, String> {
        val first = list.firstOrNull { it.jstr("topic_id").isNotEmpty() } ?: return null to ""
        return first.jstr("topic_id") to first.jstr("topic")
    }

    /** The chat of one conversation, in time order. Answers still on the server's side are shown from the job result. */
    fun build(messages: List<ChatMessage>, jobs: List<Job>): List<Bubble> {
        val jobById = jobs.associateBy { it.id }
        val sorted = messages.sortedBy { TimeUtil.parse(it.at) ?: 0L }
        val answered = sorted.filter { it.role == "assistant" }.mapNotNull { it.jobId }.toSet()
        val questionOfJob = HashMap<String, String>()
        for (m in sorted) {
            val jid = m.jobId
            if (m.role == "user" && jid != null) questionOfJob[jid] = m.content
        }
        val out = ArrayList<Bubble>()
        var lastQuestion = ""
        for (m in sorted) {
            if (m.role == "user") {
                lastQuestion = m.content
                val jid = m.jobId
                val job: Job? = if (jid != null && jid !in answered) jobById[jid] else null
                val waitingForAnswer = jid != null && jid !in answered
                var status = ""
                var failed = false
                if (waitingForAnswer) {
                    if (job == null || job.status == "queued") {
                        status = "Waiting for internet"
                    } else if (job.status == "running") {
                        status = "The tutor is answering"
                    } else if (job.status == "failed") {
                        failed = true
                        status = job.error.ifEmpty { "Sorry, this question could not be answered." }
                    }
                }
                out.add(
                    Bubble(
                        key = m.id, fromUser = true, text = m.content, time = AskLogic.timeLabel(m.at),
                        via = m.via, status = status, failed = failed,
                    ),
                )
                val result = job?.result
                val answer = result?.jstr("answer").orEmpty()
                if (job != null && job.status == "done" && answer.isNotEmpty()) {
                    val src = result?.get("sources") as? JsonArray
                    val srcObjects = src?.mapNotNull { it as? JsonObject }.orEmpty()
                    val topic = topicOf(srcObjects)
                    out.add(
                        Bubble(
                            key = "job-" + job.id, fromUser = false, text = answer, time = AskLogic.timeLabel(job.updatedAt),
                            via = m.via, topicId = topic.first, topicTitle = topic.second,
                            sources = sourcesOf(srcObjects), question = m.content,
                        ),
                    )
                }
            } else {
                val jid = m.jobId
                val topic = topicOf(m.sources)
                out.add(
                    Bubble(
                        key = m.id, fromUser = false, text = m.content, time = AskLogic.timeLabel(m.at), via = m.via,
                        offline = isOffline(m.sources), topicId = topic.first, topicTitle = topic.second,
                        sources = sourcesOf(m.sources), question = (jid?.let { questionOfJob[it] }) ?: lastQuestion,
                    ),
                )
            }
        }
        return out
    }

    fun waiting(jobs: List<Job>): List<WaitingRow> =
        jobs.filter { it.type == "tutor_question" }
            .sortedByDescending { TimeUtil.parse(it.createdAt) ?: 0L }
            .take(8)
            .map { j ->
                val (status, tone) = when (j.status) {
                    "queued" -> "Queued" to 2
                    "running" -> "Sending" to 2
                    "done" -> "Answered" to 1
                    "failed" -> "Could not answer" to 3
                    else -> j.status to 2
                }
                WaitingRow(
                    id = j.id, question = j.payload.jstr("question"), time = AskLogic.timeLabel(j.createdAt),
                    how = if (j.payload.jstr("via") == "voice") "Voice" else "Typed", status = status, tone = tone,
                )
            }

    fun waitingCount(jobs: List<Job>): Int = jobs.count { it.type == "tutor_question" && (it.status == "queued" || it.status == "running") }
}

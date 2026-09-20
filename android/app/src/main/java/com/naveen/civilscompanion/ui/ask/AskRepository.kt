package com.naveen.civilscompanion.ui.ask

import android.content.Context
import com.naveen.civilscompanion.data.model.ChatMessage
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A paragraph of the owner's own notes that answers a question without the internet. */
data class OfflineHit(val note: Note, val paragraph: String, val shared: Int, val topicTitle: String = "")

sealed interface AskOutcome {
    /** Answered on the tablet from the notes. */
    data class Offline(val text: String, val topicTitle: String) : AskOutcome

    /** Saved in the queue; the server answers when the tablet is online. */
    data object Queued : AskOutcome
}

data class ChatSummary(val id: String, val title: String, val at: String)

/**
 * Questions and answers of the Ask screen (spec 6.4, 7.7, 7.8). Used by the Ask screen and by the voice mic.
 * A question is first looked up in the notes on the tablet; if nothing good is found (or the owner wants more)
 * it becomes a `tutor_question` job, which works offline and is answered by the server later.
 */
@Singleton
class AskRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val store: RecordStore,
    private val jobs: JobRepository,
) {
    private val prefs = context.getSharedPreferences("ask_chat", Context.MODE_PRIVATE)
    private val _conversation = MutableStateFlow(prefs.getString(CURRENT, null) ?: freshConversation())

    /** The chat that new questions are added to. */
    val conversation: StateFlow<String> = _conversation.asStateFlow()

    private fun freshConversation(): String {
        val id = TimeUtil.newId()
        prefs.edit().putString(CURRENT, id).apply()
        return id
    }

    fun startNewConversation(): String {
        val id = freshConversation()
        _conversation.value = id
        return id
    }

    fun openConversation(id: String) {
        prefs.edit().putString(CURRENT, id).apply()
        _conversation.value = id
    }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        store.observe(Tables.ChatMessages, RecordQuery(k1 = conversationId, limit = 500))

    /** Earlier chats, newest first (title = the first question). */
    fun observeHistory(): Flow<List<ChatSummary>> =
        store.observe(Tables.ChatMessages, RecordQuery(order = Order.NewestFirst, limit = 600)).map { all ->
            all.filter { it.conversationId.isNotEmpty() }.groupBy { it.conversationId }.map { (id, list) ->
                val first = list.filter { it.role == "user" }.minByOrNull { TimeUtil.parse(it.at) ?: 0L }
                val last = list.maxOf { TimeUtil.parse(it.at) ?: 0L }
                ChatSummary(id, AskLogic.chatTitle(first?.content.orEmpty()), TimeUtil.toIso(last))
            }.filter { it.title.isNotEmpty() }.sortedByDescending { TimeUtil.parse(it.at) ?: 0L }
        }

    // ------------------------------------------------------------------ asking

    /**
     * Adds the question to the current chat. In the normal mode the notes are tried first (works offline);
     * otherwise, or when nothing good is found, it is queued for the tutor.
     */
    suspend fun ask(
        question: String,
        via: String,
        topicId: String? = null,
        mode: String = "",
        tryNotesFirst: Boolean = true,
    ): AskOutcome {
        val q = question.trim()
        if (tryNotesFirst && mode.isEmpty()) {
            val hit = findInNotes(q, topicId)
            if (hit != null) {
                val now = TimeUtil.parse(TimeUtil.nowIso()) ?: System.currentTimeMillis()
                val conv = _conversation.value
                store.save(
                    Tables.ChatMessages,
                    ChatMessage(id = TimeUtil.newId(), conversationId = conv, role = "user", content = q, via = via, at = TimeUtil.toIso(now)),
                )
                val source = JsonObject(
                    mapOf(
                        "note_id" to JsonPrimitive(hit.note.id),
                        "topic" to JsonPrimitive(hit.topicTitle),
                        "topic_id" to JsonPrimitive(hit.note.topicId),
                        "offline" to JsonPrimitive(true),
                    ),
                )
                store.save(
                    Tables.ChatMessages,
                    ChatMessage(
                        id = TimeUtil.newId(), conversationId = conv, role = "assistant", content = hit.paragraph,
                        sources = listOf(source), via = via, at = TimeUtil.toIso(now + 1),
                    ),
                )
                return AskOutcome.Offline(hit.paragraph, hit.topicTitle)
            }
        }
        queueForTutor(q, via, topicId, mode)
        return AskOutcome.Queued
    }

    /** Puts a question in the job queue and shows it in the chat. Returns the job id. */
    suspend fun queueForTutor(question: String, via: String, topicId: String?, mode: String): String {
        val conv = _conversation.value
        val jobId = jobs.enqueue(
            "tutor_question",
            jobPayload(
                "conversation_id" to conv,
                "question" to question,
                "topic_ids" to listOfNotNull(topicId),
                "via" to via,
                "mode" to mode,
            ),
        )
        store.save(
            Tables.ChatMessages,
            ChatMessage(
                id = TimeUtil.newId(), conversationId = conv, role = "user", content = question, via = via,
                jobId = jobId, at = TimeUtil.nowIso(),
            ),
        )
        return jobId
    }

    /** "Save to notes": the server merges the text into the topic's note (job `note_merge`). */
    suspend fun saveToNotes(text: String, topicId: String?, title: String) {
        jobs.enqueue(
            "note_merge",
            jobPayload("topic_id" to topicId, "text" to text, "title" to title, "mode" to "merge"),
        )
    }

    // ------------------------------------------------------------------ notes on the tablet

    suspend fun findInNotes(question: String, topicId: String?): OfflineHit? {
        val kw = AskLogic.keywords(question)
        if (kw.isEmpty()) return null
        val found = LinkedHashMap<String, Note>()
        if (topicId != null) {
            for (n in store.list(Tables.Notes, RecordQuery(k1 = topicId, limit = 20))) found[n.id] = n
        }
        for (word in kw.sortedByDescending { it.length }.take(4)) {
            for (n in store.list(Tables.Notes, RecordQuery(contains = AskLogic.stem(word), limit = 30))) found[n.id] = n
        }
        var best: OfflineHit? = null
        for (note in found.values) {
            if (note.contentMd.isBlank()) continue
            val b = AskLogic.bestParagraph(note.contentMd, kw) ?: continue
            val current = best
            if (current == null || b.shared > current.shared) best = OfflineHit(note, b.paragraph, b.shared)
        }
        val hit = best ?: return null
        if (!AskLogic.goodEnough(kw.size, hit.shared)) return null
        val title = store.get(Tables.Topics, hit.note.topicId)?.title ?: "Your notes"
        return hit.copy(topicTitle = title)
    }

    private companion object {
        const val CURRENT = "current"
    }
}

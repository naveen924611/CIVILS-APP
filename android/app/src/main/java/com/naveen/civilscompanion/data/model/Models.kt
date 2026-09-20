package com.naveen.civilscompanion.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * One class per synced table. Field names are the server's JSON keys (backend/app/db/models_v2.py).
 * Timestamps are ISO-8601 text ending in Z (use TimeUtil). Every model has id, updatedAt and deleted;
 * updatedAt is set by RecordStore.save, so leave it alone. All fields have defaults so a missing
 * key never breaks reading. Fields the tablet may not write are marked "server" (ignored on push).
 */

@Serializable
data class Exam(
    val id: String,
    val name: String = "",
    val stage: String = "Prelims",
    val date: String? = null, // null = "Date not announced"
    @SerialName("is_tentative") val isTentative: Boolean = true,
    val weight: Double = 1.0,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** A syllabus turned into a topic tree by the server's AI. status: processing|pending|approved|failed. */
@Serializable
data class SyllabusImport(
    val id: String,
    val exam: String = "",
    val title: String = "",
    val status: String = "processing",
    @SerialName("tree_json") val treeJson: JsonElement? = null, // server: nested [{title, level, children:[...]}]
    @SerialName("document_id") val documentId: String? = null,
    val note: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Topic(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("syllabus_id") val syllabusId: String? = null, // server
    val title: String = "",
    val level: Int = 0, // 0 paper, 1 subject, 2 topic, 3 subtopic
    val paper: String = "",
    @SerialName("exam_tags") val examTags: List<String> = emptyList(),
    @SerialName("est_hours") val estHours: Double = 1.0,
    val status: String = "not_started", // not_started|in_progress|studied|revised|strong
    val importance: Double = 0.0, // server, 0-10
    val strength: Double = 0.0, // 0-1
    val approved: Boolean = false,
    val position: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class LibDocument(
    val id: String,
    val title: String = "",
    val type: String = "pdf", // pdf|image|scan|note|recommended (server)
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("file_path") val filePath: String = "", // server
    val pages: Int = 0, // server
    @SerialName("processing_status") val processingStatus: String = "uploaded", // server
    @SerialName("status_detail") val statusDetail: String = "", // server
    @SerialName("reading_position") val readingPosition: JsonObject = JsonObject(emptyMap()), // {"page":3,"sentence":12}
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val language: String = "en",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** Text of one page. source: text|ocr|gemini|device_ocr. */
@Serializable
data class DocPage(
    val id: String,
    @SerialName("document_id") val documentId: String = "",
    val page: Int = 1,
    val text: String = "",
    val source: String = "text",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Note(
    val id: String,
    @SerialName("topic_id") val topicId: String = "",
    val version: Int = 1, // server
    @SerialName("content_md") val contentMd: String = "",
    // {"overview","key_points":[],"must_remember":[],"mains_angle","mcqs":[],"cards":[]} (server)
    val sections: JsonObject = JsonObject(emptyMap()),
    val sources: List<JsonObject> = emptyList(), // [{"document_id","title","page"}] (server)
    @SerialName("owner_edited") val ownerEdited: Boolean = false,
    val status: String = "ready", // ready|generating|failed|no_material (server)
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** kind: point|must|card */
@Serializable
data class Highlight(
    val id: String,
    @SerialName("document_id") val documentId: String? = null,
    val page: Int = 1,
    val text: String = "",
    val kind: String = "point",
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Card(
    val id: String,
    val front: String = "",
    val back: String = "",
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("source_type") val sourceType: String = "news",
    @SerialName("source_id") val sourceId: String? = null,
    val group: String = "Current affairs",
    @SerialName("fsrs_state_json") val fsrsState: JsonObject? = null, // FSRS memory state, see srs/Fsrs.kt
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** grade: 1 again, 2 hard, 3 good, 4 easy. stateJson = the card state before this review (lets the owner undo). */
@Serializable
data class Review(
    val id: String,
    @SerialName("card_id") val cardId: String = "",
    val grade: Int = 3,
    @SerialName("reviewed_at") val reviewedAt: String = "",
    @SerialName("state_json") val stateJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** type: slot|pinned_subject|daily_group|max_cards|sunday_review|last_month */
@Serializable
data class RevisionRule(
    val id: String,
    val type: String = "",
    @SerialName("params_json") val params: JsonObject = JsonObject(emptyMap()),
    val enabled: Boolean = true,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** The owner's chosen order of card groups for one date (YYYY-MM-DD). */
@Serializable
data class RevisionOrder(
    val id: String,
    val date: String = "",
    @SerialName("group_order") val groupOrder: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** All fields are server-written. sourceType: news|note|pyq|mock */
@Serializable
data class Mcq(
    val id: String,
    @SerialName("source_type") val sourceType: String = "note",
    @SerialName("source_id") val sourceId: String? = null,
    val question: String = "",
    val options: List<String> = emptyList(),
    @SerialName("answer_index") val answerIndex: Int = 0,
    val explanation: String = "",
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** chosen -1 = skipped. confidence: sure|unsure|guess. mistakeType: didnt_know|confused|silly. */
@Serializable
data class Attempt(
    val id: String,
    @SerialName("mcq_id") val mcqId: String = "",
    @SerialName("test_id") val testId: String? = null,
    val chosen: Int = -1,
    val correct: Boolean = false,
    val confidence: String = "",
    @SerialName("mistake_type") val mistakeType: String = "",
    val at: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** The mistake book: leaves after two correct answers in a row, spaced apart. */
@Serializable
data class Mistake(
    val id: String,
    @SerialName("mcq_id") val mcqId: String = "",
    @SerialName("mistake_type") val mistakeType: String = "didnt_know",
    @SerialName("your_answer") val yourAnswer: Int = -1,
    val streak: Int = 0,
    @SerialName("last_answered_at") val lastAnsweredAt: String? = null,
    @SerialName("next_due_at") val nextDueAt: String? = null,
    val resolved: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** kind: weekly|topic|past_paper|mistakes. status: generating|ready|in_progress|done|analysed. */
@Serializable
data class MockTest(
    val id: String,
    val kind: String = "weekly",
    val title: String = "",
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("mcq_ids") val mcqIds: List<String> = emptyList(), // server
    @SerialName("duration_min") val durationMin: Int = 30, // server
    @SerialName("negative_marking") val negativeMarking: Boolean = false,
    val status: String = "ready",
    val score: Double? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("analysis_json") val analysis: JsonObject? = null, // server
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Pyq(
    val id: String,
    val exam: String = "",
    val year: Int = 0,
    val paper: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    @SerialName("answer_index") val answerIndex: Int = -1,
    @SerialName("topic_ids") val topicIds: List<String> = emptyList(),
    @SerialName("document_id") val documentId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** status: recorded|queued|done|failed. feedback is written by the server. */
@Serializable
data class ExplainSession(
    val id: String,
    @SerialName("topic_id") val topicId: String? = null,
    val transcript: String = "",
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("feedback_json") val feedback: JsonObject? = null,
    val status: String = "recorded",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** A written answer (photos are uploaded separately). kind: mains|essay. status: draft|queued|done|failed. */
@Serializable
data class AnswerSubmission(
    val id: String,
    val question: String = "",
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("word_limit") val wordLimit: Int = 250,
    val kind: String = "mains",
    @SerialName("image_paths") val imagePaths: List<String> = emptyList(), // server
    @SerialName("feedback_json") val feedback: JsonObject? = null, // server
    val score: Double? = null, // server
    val status: String = "draft",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class Video(
    val id: String,
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("youtube_id") val youtubeId: String = "",
    val title: String = "",
    val channel: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val embeddable: Boolean = true,
    val watched: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class VideoNote(
    val id: String,
    @SerialName("video_id") val videoId: String = "",
    val seconds: Int = 0,
    val text: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** blocks: [{"id","kind","start","minutes","title","detail","topic_id","ref"}]; completion: {"block_id":"done"|"skipped"}. */
@Serializable
data class DailyPlan(
    val id: String,
    val date: String = "",
    @SerialName("blocks_json") val blocks: List<JsonObject> = emptyList(), // server
    @SerialName("completion_json") val completion: JsonObject = JsonObject(emptyMap()),
    val summary: String = "", // server
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class FocusSession(
    val id: String,
    @SerialName("topic_id") val topicId: String? = null,
    @SerialName("block_id") val blockId: String? = null,
    @SerialName("started_at") val startedAt: String = "",
    val minutes: Int = 0,
    @SerialName("completion_pct") val completionPct: Int = 100,
    val style: String = "50+10",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** Everything except id/updatedAt/deleted is written by the server. */
@Serializable
data class WeeklyReport(
    val id: String,
    @SerialName("week_start") val weekStart: String = "",
    @SerialName("data_json") val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("audio_path") val audioPath: String? = null,
    val status: String = "ready",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** Revision sheet, one page per topic (server-written). */
@Serializable
data class Sheet(
    val id: String,
    @SerialName("topic_id") val topicId: String = "",
    @SerialName("content_md") val contentMd: String = "",
    val sections: JsonObject = JsonObject(emptyMap()),
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("pdf_path") val pdfPath: String? = null,
    @SerialName("audio_seconds") val audioSeconds: Int = 0,
    val status: String = "ready",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** Monthly current-affairs compilation (server-written). month = YYYY-MM. */
@Serializable
data class Compilation(
    val id: String,
    val month: String = "",
    val title: String = "",
    @SerialName("content_md") val contentMd: String = "",
    @SerialName("pdf_path") val pdfPath: String? = null,
    val status: String = "ready",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** The optional library-day list. */
@Serializable
data class LibraryItem(
    val id: String,
    val book: String = "",
    val why: String = "",
    val chapters: List<String> = emptyList(),
    @SerialName("visit_date") val visitDate: String? = null,
    val done: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** Recommended free official material or optional book (server-written). kind: official|book. */
@Serializable
data class Material(
    val id: String,
    val key: String = "",
    val kind: String = "official",
    val title: String = "",
    val why: String = "",
    @SerialName("needed_for") val neededFor: String = "",
    val url: String = "",
    val subject: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/**
 * The offline job queue. Create with status "queued" (JobRepository.enqueue); the server fills result/error.
 * status: queued|running|done|failed.
 */
@Serializable
data class Job(
    val id: String,
    val type: String = "",
    @SerialName("payload_json") val payload: JsonObject = JsonObject(emptyMap()),
    val status: String = "queued",
    @SerialName("result_json") val result: JsonObject? = null,
    val error: String = "",
    val notified: Boolean = false, // server
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** role: user|assistant. via: text|voice. */
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String = "",
    val role: String = "user",
    val content: String = "",
    val sources: List<JsonObject> = emptyList(),
    val via: String = "text",
    @SerialName("job_id") val jobId: String? = null,
    val at: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

/** kind: vocab|passage|translation|template (server-written). */
@Serializable
data class TeluguItem(
    val id: String,
    val kind: String = "vocab",
    val level: Int = 1,
    @SerialName("content_json") val content: JsonObject = JsonObject(emptyMap()),
    val position: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class TeluguProgress(
    val id: String,
    @SerialName("item_id") val itemId: String = "",
    val done: Boolean = false,
    val score: Double? = null,
    val answer: String = "",
    val at: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

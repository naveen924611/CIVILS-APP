package com.naveen.civilscompanion.data.records

import com.naveen.civilscompanion.data.model.AnswerSubmission
import com.naveen.civilscompanion.data.model.Attempt
import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.ChatMessage
import com.naveen.civilscompanion.data.model.Compilation
import com.naveen.civilscompanion.data.model.DailyPlan
import com.naveen.civilscompanion.data.model.DocPage
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.ExplainSession
import com.naveen.civilscompanion.data.model.FocusSession
import com.naveen.civilscompanion.data.model.Highlight
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.LibraryItem
import com.naveen.civilscompanion.data.model.Material
import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.MockTest
import com.naveen.civilscompanion.data.model.Mistake
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.data.model.Pyq
import com.naveen.civilscompanion.data.model.Review
import com.naveen.civilscompanion.data.model.RevisionOrder
import com.naveen.civilscompanion.data.model.RevisionRule
import com.naveen.civilscompanion.data.model.Sheet
import com.naveen.civilscompanion.data.model.SyllabusImport
import com.naveen.civilscompanion.data.model.TeluguItem
import com.naveen.civilscompanion.data.model.TeluguProgress
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.model.Video
import com.naveen.civilscompanion.data.model.VideoNote
import com.naveen.civilscompanion.data.model.WeeklyReport

/** Every synced table, with the keys indexed for filtering (k1, k2), sorting/ranges (n1) and search (text). */
object Tables {
    val Exams = Table("exams", Exam.serializer(), { it.id }, k1 = "stage", n1 = "date", text = listOf("name"))
    val SyllabusImports = Table("syllabus_imports", SyllabusImport.serializer(), { it.id }, k1 = "status", text = listOf("title"))
    val Topics = Table("topics", Topic.serializer(), { it.id }, k1 = "parent_id", k2 = "status", n1 = "position", text = listOf("title", "paper"))
    val Documents = Table("documents", LibDocument.serializer(), { it.id }, k1 = "type", k2 = "topic_id", n1 = "updated_at", text = listOf("title"))
    val DocPages = Table("doc_pages", DocPage.serializer(), { it.id }, k1 = "document_id", n1 = "page", text = listOf("text"))
    val Notes = Table("notes", Note.serializer(), { it.id }, k1 = "topic_id", k2 = "status", n1 = "version", text = listOf("content_md"))
    val Highlights = Table("highlights", Highlight.serializer(), { it.id }, k1 = "document_id", k2 = "topic_id", n1 = "page", text = listOf("text"))
    val Cards = Table("cards", Card.serializer(), { it.id }, k1 = "topic_id", k2 = "source_id", n1 = "due_at", text = listOf("front", "back", "group"))
    val Reviews = Table("reviews", Review.serializer(), { it.id }, k1 = "card_id", n1 = "reviewed_at")
    val RevisionRules = Table("revision_rules", RevisionRule.serializer(), { it.id }, k1 = "type", n1 = "enabled")
    val RevisionOrders = Table("revision_order", RevisionOrder.serializer(), { it.id }, k1 = "date")
    val Mcqs = Table("mcqs", Mcq.serializer(), { it.id }, k1 = "topic_id", k2 = "source_type", text = listOf("question"))
    val Attempts = Table("attempts", Attempt.serializer(), { it.id }, k1 = "mcq_id", k2 = "test_id", n1 = "at")
    val Mistakes = Table("mistakes", Mistake.serializer(), { it.id }, k1 = "mcq_id", k2 = "mistake_type", n1 = "next_due_at")
    val Tests = Table("tests", MockTest.serializer(), { it.id }, k1 = "kind", k2 = "status", n1 = "scheduled_for", text = listOf("title"))
    val Pyqs = Table("pyqs", Pyq.serializer(), { it.id }, k1 = "exam", k2 = "paper", n1 = "year", text = listOf("question"))
    val ExplainSessions = Table("explain_sessions", ExplainSession.serializer(), { it.id }, k1 = "topic_id", k2 = "status", n1 = "created_at")
    val Answers = Table("answers", AnswerSubmission.serializer(), { it.id }, k1 = "topic_id", k2 = "status", n1 = "created_at", text = listOf("question"))
    val Videos = Table("videos", Video.serializer(), { it.id }, k1 = "topic_id", k2 = "youtube_id", n1 = "watched", text = listOf("title", "channel"))
    val VideoNotes = Table("video_notes", VideoNote.serializer(), { it.id }, k1 = "video_id", n1 = "seconds", text = listOf("text"))
    val DailyPlans = Table("daily_plans", DailyPlan.serializer(), { it.id }, k1 = "date")
    val FocusSessions = Table("focus_sessions", FocusSession.serializer(), { it.id }, k1 = "topic_id", n1 = "started_at")
    val WeeklyReports = Table("weekly_reports", WeeklyReport.serializer(), { it.id }, k1 = "week_start")
    val Sheets = Table("sheets", Sheet.serializer(), { it.id }, k1 = "topic_id", k2 = "status", text = listOf("content_md"))
    val Compilations = Table("compilations", Compilation.serializer(), { it.id }, k1 = "month", text = listOf("title"))
    val LibraryList = Table("library_list", LibraryItem.serializer(), { it.id }, k1 = "visit_date", n1 = "done", text = listOf("book"))
    val Materials = Table("materials", Material.serializer(), { it.id }, k1 = "kind", k2 = "subject", text = listOf("title"))
    val Jobs = Table("jobs", Job.serializer(), { it.id }, k1 = "type", k2 = "status", n1 = "created_at")
    val ChatMessages = Table("chat_messages", ChatMessage.serializer(), { it.id }, k1 = "conversation_id", n1 = "at", text = listOf("content"))
    val TeluguItems = Table("telugu_items", TeluguItem.serializer(), { it.id }, k1 = "kind", k2 = "level", n1 = "position")
    val TeluguProgressRows = Table("telugu_progress", TeluguProgress.serializer(), { it.id }, k1 = "item_id", n1 = "at")

    /** name -> table, used by sync to index rows without knowing their type. */
    val all: Map<String, Table<*>> = listOf(
        Exams, SyllabusImports, Topics, Documents, DocPages, Notes, Highlights, Cards, Reviews, RevisionRules,
        RevisionOrders, Mcqs, Attempts, Mistakes, Tests, Pyqs, ExplainSessions, Answers, Videos, VideoNotes,
        DailyPlans, FocusSessions, WeeklyReports, Sheets, Compilations, LibraryList, Materials, Jobs,
        ChatMessages, TeluguItems, TeluguProgressRows,
    ).associateBy { it.name }
}

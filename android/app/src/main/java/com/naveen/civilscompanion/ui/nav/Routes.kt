package com.naveen.civilscompanion.ui.nav

/**
 * Every screen address in the app. Use these constants (never typed strings) so screens can open each other:
 *   nav.navigate(Routes.readDoc(id))
 * The ten rail screens come first; the rest are opened from inside other screens.
 */
object Routes {
    const val TODAY = "today"
    const val BRIEFS = "briefs"
    const val LIBRARY = "library"
    const val READ = "read"
    const val NOTES = "notes"
    const val REVISE = "revise"
    const val ASK = "ask"
    const val FOCUS = "focus"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"

    // Library / Reader / Capture (M3, M4)
    const val READ_DOC = "read/{docId}"
    fun readDoc(docId: String) = "read/$docId"
    const val CAPTURE = "capture"

    // Notes / Syllabus (M4)
    const val NOTE_TOPIC = "notes/{topicId}"
    fun noteTopic(topicId: String) = "notes/$topicId"
    const val SYLLABUS = "syllabus"
    const val SYLLABUS_REVIEW = "syllabus/{importId}"
    fun syllabusReview(importId: String) = "syllabus/$importId"

    // Revision (M5)
    const val REVISE_SESSION = "revise/session"
    const val REVISE_RULES = "revise/rules"

    // Planner (M5), setup and storage (M5, M7)
    const val PLANNER = "planner"
    const val EXAMS = "exams"
    const val MATERIALS = "materials"
    const val STORAGE = "storage"

    // Tests and mistakes (M8)
    const val TESTS = "tests"
    const val TEST_RUN = "test/{testId}"
    fun testRun(testId: String) = "test/$testId"
    const val TEST_RESULT = "test/{testId}/result"
    fun testResult(testId: String) = "test/$testId/result"
    const val MISTAKES = "mistakes"

    // Explain-back and answer writing (M9)
    const val EXPLAIN = "explain"
    const val EXPLAIN_TOPIC = "explain/{topicId}"
    fun explainTopic(topicId: String) = "explain/$topicId"
    const val ANSWERS = "answers"
    const val ANSWER = "answer/{id}"
    fun answer(id: String) = "answer/$id"

    // Sheets, weekly report, videos, Telugu, compilation (M10-M12)
    const val SHEETS = "sheets"
    const val SHEET = "sheet/{topicId}"
    fun sheet(topicId: String) = "sheet/$topicId"
    const val REPORT = "report"
    const val VIDEOS = "videos"
    const val VIDEO = "video/{id}"
    fun video(id: String) = "video/$id"
    const val TELUGU = "telugu"
    const val COMPILATION = "compilation"

    // SI (Civil) goal: dates, eligibility, checklist, body check, PET log
    const val GOALS = "goals"

    /** Which rail item is highlighted while a screen is open. */
    fun railFor(route: String?): Destination {
        val base = route?.substringBefore('/') ?: return Destination.Today
        return when (base) {
            READ, CAPTURE -> Destination.Read
            LIBRARY, MATERIALS -> Destination.Library
            NOTES, "syllabus" -> Destination.Notes
            REVISE, "sheets", "sheet", REPORT, MISTAKES -> Destination.Revise
            ASK, TESTS, "test", EXPLAIN, ANSWERS, "answer", TELUGU -> Destination.Ask
            FOCUS, VIDEOS, "video" -> Destination.Focus
            BRIEFS, COMPILATION -> Destination.Briefs
            ALERTS -> Destination.Alerts
            SETTINGS, EXAMS, STORAGE, GOALS -> Destination.Settings
            else -> Destination.entries.firstOrNull { it.route == base } ?: Destination.Today
        }
    }
}

package com.naveen.civilscompanion.ui.notes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What the server says the note is missing and what it found in the news (all read from `Note.sections`). */
data class NewsCard(val id: String, val title: String, val summary: String, val url: String, val source: String, val publishedAt: String)

data class SourceRef(val documentId: String, val title: String, val page: Int)

/** The text of a note split into the owner's part and the "Suggested additions" block the server may add at the end. */
data class NoteSplit(val body: String, val suggestions: List<String>)

/** Pure helpers for the Notes screens (no Android): suggestion block, sections, sources, dates. */
object NoteLogic {
    const val SUGGESTED_HEADING = "## Suggested additions"
    const val SUGGESTED_INTRO = "_Proposed from your material. Move what you want into your notes above, then delete this block._"
    const val NO_MATERIAL_TEXT =
        "Not found in your material yet. Add a book, PDF or scanned pages about this topic in the Library, then tap Make notes again."
    private const val MUST_PREFIX = "Must remember: "
    private val nextHeading = Regex("\n#{1,2} ")

    /** Same reading rule as the server: the block runs from its heading to the next heading; its "- " lines are the items. */
    fun split(content: String): NoteSplit {
        val at = content.indexOf(SUGGESTED_HEADING)
        if (at < 0) return NoteSplit(content, emptyList())
        val before = content.substring(0, at).trimEnd()
        val rest = content.substring(at + SUGGESTED_HEADING.length)
        val next = nextHeading.find(rest)
        val block = if (next != null) rest.substring(0, next.range.first) else rest
        val after = if (next != null) rest.substring(next.range.first) else ""
        val items = block.lines().filter { it.startsWith("- ") }.map { it.substring(2).trim() }.filter { it.isNotEmpty() }
        val body = (before + if (after.isNotBlank()) "\n\n" + after.trim() else "").trim()
        return NoteSplit(body, items)
    }

    private fun withBlock(body: String, items: List<String>): String {
        if (items.isEmpty()) return body.trimEnd() + "\n"
        val block = SUGGESTED_HEADING + "\n\n" + SUGGESTED_INTRO + "\n\n" + items.joinToString("\n") { "- $it" }
        return (body.trimEnd() + "\n\n" + block).trim() + "\n"
    }

    /** How an accepted suggestion is written into the owner's text. */
    fun asBullet(item: String): String =
        if (item.startsWith(MUST_PREFIX)) "- **Must remember:** " + item.removePrefix(MUST_PREFIX) else "- $item"

    private fun appendBullets(body: String, items: List<String>): String {
        if (items.isEmpty()) return body
        val trimmed = body.trimEnd()
        val lastLine = trimmed.lines().lastOrNull().orEmpty()
        val gap = when {
            trimmed.isEmpty() -> ""
            lastLine.startsWith("- ") -> "\n"
            else -> "\n\n"
        }
        return trimmed + gap + items.joinToString("\n") { asBullet(it) }
    }

    /** The owner adds one suggestion to his text; it leaves the block. */
    fun accept(content: String, item: String): String {
        val parts = split(content)
        return withBlock(appendBullets(parts.body, listOf(item)), parts.suggestions.filter { it != item })
    }

    fun acceptAll(content: String): String {
        val parts = split(content)
        return withBlock(appendBullets(parts.body, parts.suggestions), emptyList())
    }

    /** The owner does not want one suggestion. */
    fun dismiss(content: String, item: String): String {
        val parts = split(content)
        return withBlock(parts.body, parts.suggestions.filter { it != item })
    }

    fun dismissAll(content: String): String = withBlock(split(content).body, emptyList())

    // ------------------------------------------------------------------ sections written by the server

    fun strings(sections: JsonObject, key: String): List<String> =
        (sections[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

    fun mustRemember(sections: JsonObject): List<String> = strings(sections, "must_remember")

    fun news(sections: JsonObject): List<NewsCard> =
        (sections["in_the_news"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = field(o, "title")
            if (title.isEmpty()) return@mapNotNull null
            NewsCard(field(o, "id"), title, field(o, "summary"), field(o, "url"), field(o, "source"), field(o, "published_at"))
        }

    fun sources(list: List<JsonObject>): List<SourceRef> = list.mapNotNull { o ->
        val doc = field(o, "document_id")
        val title = field(o, "title")
        if (doc.isEmpty() && title.isEmpty()) return@mapNotNull null
        SourceRef(doc, title.ifEmpty { "A document in your library" }, (o["page"] as? JsonPrimitive)?.intOrNull ?: 1)
    }.distinctBy { it.documentId to it.page }

    private fun field(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    // ------------------------------------------------------------------ small texts

    /** "Sources: Laxmikanth Polity, p. 7". */
    fun sourceLine(s: SourceRef): String = "${s.title}, page ${s.page}"

    /** When the next card of a topic is due, in words. `dueMillis` are the due times of its cards (any order). */
    fun nextRevision(dueMillis: List<Long>, nowMillis: Long, dayMillis: Long = 86_400_000L): String {
        val next = dueMillis.minOrNull() ?: return "No cards yet"
        val days = Math.floorDiv(next - nowMillis, dayMillis).toInt()
        return when {
            next <= nowMillis -> "Due now"
            days <= 0 -> "Later today"
            days == 1 -> "Tomorrow"
            else -> "In $days days"
        }
    }

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** A short readable date from the server's ISO time ("2026-09-18T07:00:00Z" becomes "18 Sep"). Unreadable text is returned as it is. */
    fun shortDate(iso: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return iso
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = m.groupValues[2].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return iso
        return "${m.groupValues[3].toInt()} $month"
    }

    /** A short piece of a note around the first place the searched words appear (empty when they are not in it). */
    fun snippet(content: String, query: String, radius: Int = 60): String {
        val word = query.trim().split(' ').firstOrNull { it.isNotEmpty() } ?: return ""
        val flat = content.replace(Regex("\\s+"), " ")
        val at = flat.indexOf(word, ignoreCase = true)
        if (at < 0) return ""
        val from = maxOf(0, at - radius)
        val to = minOf(flat.length, at + word.length + radius)
        return (if (from > 0) "..." else "") + flat.substring(from, to).trim() + (if (to < flat.length) "..." else "")
    }

    /** True when the text is only the "no material" message (so the editor starts empty). */
    fun isBlankNote(content: String): Boolean = content.isBlank() || content.trim() == NO_MATERIAL_TEXT

    /** Speaks the note: markdown marks removed, one sentence per piece. */
    fun sentencesForSpeech(md: String): List<String> {
        val plain = md.replace(Regex("[#>*_`]+"), " ").replace(Regex("(?m)^\\s*-\\s+"), "")
        return plain.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.length > 1 }
    }
}

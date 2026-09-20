package com.naveen.civilscompanion.ui.ask

import com.naveen.civilscompanion.data.records.TimeUtil
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure helpers for the Ask screen (no Android classes, so they can be tested on a computer). */
object AskLogic {
    /** Tutor styles on the Ask screen: code sent to the server, label shown. Code "" is the normal short answer. */
    val MODES: List<Pair<String, String>> = listOf(
        "" to "Answer",
        "simple" to "Explain simply",
        "depth" to "In depth",
        "quiz" to "Quiz me",
        "evaluate" to "Evaluate my answer",
    )

    private val stopWords = setOf(
        "the", "and", "for", "are", "was", "were", "what", "which", "who", "whom", "whose", "when", "where", "why",
        "how", "does", "did", "can", "could", "would", "should", "will", "shall", "with", "from", "that", "this",
        "these", "those", "about", "into", "than", "then", "them", "they", "you", "your", "his", "her", "its",
        "tell", "explain", "give", "say", "please", "mean", "means", "meaning", "define", "difference", "between",
        "have", "has", "had", "been", "being", "not", "any", "all", "some", "also", "very", "much", "many",
        "there", "their", "here", "let", "know", "want", "need", "like", "more", "most", "over", "under",
    )

    private val wordRe = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val citeRe = Regex("\\[\\d{1,2}]")
    private val linkRe = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val teluguRe = Regex("[\\u0C00-\\u0C7F]")

    /** Important words of a question, lower case, without common words. */
    fun keywords(text: String): List<String> =
        wordRe.findAll(text.lowercase()).map { it.value }
            .filter { (it.length >= 3 || (it.length >= 2 && it.any(Char::isDigit))) && it !in stopWords }
            .distinct().toList()

    /** Plural-tolerant word compare key ("rights" and "right" match). */
    fun stem(word: String): String =
        if (word.length > 4 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    fun wordSet(text: String): Set<String> =
        wordRe.findAll(text.lowercase()).map { stem(it.value) }.toSet()

    /** The paragraph of a note that shares the most question words, with that number of shared words. */
    data class Best(val paragraph: String, val shared: Int)

    fun bestParagraph(content: String, keywords: List<String>): Best? {
        if (keywords.isEmpty()) return null
        val want = keywords.map { stem(it) }.toSet()
        var best: Best? = null
        for (raw in content.split(Regex("\\n\\s*\\n"))) {
            val para = cleanParagraph(raw)
            if (para.length < 20) continue
            val words = wordSet(para)
            val shared = want.count { it in words }
            val current = best
            if (shared > 0 && (current == null || shared > current.shared)) best = Best(para, shared)
        }
        return best
    }

    /** Removes heading marks from the start of lines; keeps bullets. */
    fun cleanParagraph(raw: String): String =
        raw.lines().joinToString("\n") { it.trimEnd().replace(Regex("^#{1,6}\\s+"), "") }.trim().take(900)

    /** Is a note paragraph close enough to the question to be shown as an offline answer? */
    fun goodEnough(keywordCount: Int, shared: Int): Boolean = when {
        keywordCount <= 0 -> false
        keywordCount == 1 -> shared >= 1
        keywordCount == 2 -> shared >= 2
        else -> shared >= 2 && shared * 2 >= keywordCount
    }

    /** Text for the text-to-speech engine: no Markdown marks, no [1] citation numbers. */
    fun plainForSpeech(md: String): String {
        var t = stripCites(md.replace(linkRe, "$1"))
        t = t.replace(Regex("(?m)^\\s*#{1,6}\\s+"), "").replace(Regex("(?m)^\\s*[-*+•]\\s+"), "")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** The answer as shown on screen: citation numbers removed (the sources are listed under the answer). */
    fun forDisplay(md: String): String = stripCites(md).replace(Regex("[ \\t]+\\n"), "\n").trim()

    /** Removes [1] style numbers and the space they leave before punctuation. */
    private fun stripCites(text: String): String =
        text.replace(citeRe, "").replace(Regex("[ \\t]+([.,;:!?])"), "$1")

    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            trimmed.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        }
        return out
    }

    fun isTelugu(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { teluguRe.matches(it.toString()) } * 10 > letters.length * 3
    }

    /** "3:05 pm" in India time, from an ISO time. Empty when the time cannot be read. */
    fun timeLabel(iso: String): String {
        val millis = TimeUtil.parse(iso) ?: return ""
        val zoned = Instant.ofEpochMilli(millis).atZone(TimeUtil.india)
        return DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(zoned).lowercase(Locale.ENGLISH)
    }

    /** "20 Sep, 3:05 pm" for older items. */
    fun dayTimeLabel(iso: String): String {
        val millis = TimeUtil.parse(iso) ?: return ""
        val zoned = Instant.ofEpochMilli(millis).atZone(TimeUtil.india)
        return DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH).format(zoned).replace("AM", "am").replace("PM", "pm")
    }

    /** Short title for a chat in the history list. */
    fun chatTitle(firstQuestion: String): String {
        val one = firstQuestion.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= 60) one else one.take(57).trimEnd() + "..."
    }
}

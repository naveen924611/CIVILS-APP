package com.naveen.civilscompanion.ui.explain

import com.naveen.civilscompanion.ui.ask.jbool
import com.naveen.civilscompanion.ui.ask.jint
import com.naveen.civilscompanion.ui.ask.jlist
import com.naveen.civilscompanion.ui.ask.jobj
import com.naveen.civilscompanion.ui.ask.jstr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Pure helpers for "Explain it back" (spec 6.16). No Android classes, so they can be tested on a computer. */
object ExplainLogic {
    const val MIN_WORDS = 8

    data class Fix(val said: String, val correct: String)

    data class Feedback(
        val covered: List<String>,
        val missed: List<String>,
        val fixes: List<Fix>,
        val coveredCount: Int,
        val total: Int,
        val model: String,
        val fromMaterial: Boolean,
    )

    /** feedback_json written by the server; null when it is not there (yet). */
    fun parseFeedback(o: JsonObject?): Feedback? {
        if (o == null || o.isEmpty()) return null
        val covered = o.jlist("covered")
        val missed = o.jlist("missed")
        val fixes = (o["needs_correcting"] as? JsonArray).orEmpty().mapNotNull { e ->
            val item = e as? JsonObject ?: return@mapNotNull null
            val said = item.jstr("said")
            val correct = item.jstr("correct")
            if (said.isEmpty() && correct.isEmpty()) null else Fix(said, correct)
        }
        val coverage = o.jobj("coverage")
        val count = coverage?.jint("covered") ?: covered.size
        val total = coverage?.jint("total") ?: (covered.size + missed.size)
        return Feedback(covered, missed, fixes, count, total, o.jstr("model_explanation"), o.jbool("from_your_material"))
    }

    fun coverageText(f: Feedback): String =
        if (f.total <= 0) "No key points to compare" else "Covered ${f.coveredCount} of ${f.total} key points"

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun canSubmit(transcript: String): Boolean = wordCount(transcript) >= MIN_WORDS

    /** 3:05 for 185 seconds. */
    fun durationLabel(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private val stopWords = setOf(
        "which", "their", "there", "these", "those", "would", "could", "should", "about", "under", "between",
        "because", "through", "during", "other", "while", "where", "after", "before", "being", "having",
    )

    /**
     * Turns a missed key point into a fill-in-the-blank card: the most informative word (a number, or the longest
     * word) is hidden on the front and given on the back together with the whole point.
     */
    fun cloze(point: String, topic: String): Pair<String, String> {
        val clean = point.replace(Regex("\\s+"), " ").trim()
        val words = clean.split(' ')
        var pick = -1
        var bestDigits = 1
        for ((i, w) in words.withIndex()) {
            val digits = w.count { it.isLetterOrDigit() }
            if (w.any { it.isDigit() } && digits > bestDigits) {
                bestDigits = digits
                pick = i
            }
        }
        if (pick < 0) {
            var bestLen = 4
            for ((i, w) in words.withIndex()) {
                val core = w.filter { it.isLetter() }
                if (core.length > bestLen && core.lowercase() !in stopWords) {
                    bestLen = core.length
                    pick = i
                }
            }
        }
        if (pick < 0) {
            val head = words.take(6).joinToString(" ")
            return "$topic: complete this point. \"$head ...\"" to clean
        }
        val word = words[pick]
        val core = word.trim { !it.isLetterOrDigit() }
        val front = words.mapIndexed { i, w -> if (i == pick) w.replace(core, "_____") else w }.joinToString(" ")
        return "$topic: $front" to "$core\n\n$clean"
    }
}

package com.naveen.civilscompanion.ui.answers

import com.naveen.civilscompanion.ui.ask.jbool
import com.naveen.civilscompanion.ui.ask.jint
import com.naveen.civilscompanion.ui.ask.jlist
import com.naveen.civilscompanion.ui.ask.jobj
import com.naveen.civilscompanion.ui.ask.jstr
import kotlinx.serialization.json.JsonObject

/** Pure helpers for answer writing (spec 6.21). No Android classes, so they can be tested on a computer. */
object AnswerLogic {
    const val MIN_TYPED_WORDS = 5

    data class Feedback(
        val readable: Boolean,
        val intro: String,
        val body: String,
        val conclusion: String,
        val coverage: String,
        val examples: String,
        val words: Int?,
        val limit: Int?,
        val lengthComment: String,
        val presentation: String,
        val strengths: List<String>,
        val improvements: List<String>,
        val outline: List<String>,
        val transcript: String,
    )

    /** feedback_json written by the server; null when it is not there yet. */
    fun parseFeedback(o: JsonObject?): Feedback? {
        if (o == null || o.isEmpty()) return null
        val structure = o.jobj("structure")
        val length = o.jobj("word_limit")
        return Feedback(
            readable = if (o.containsKey("readable")) o.jbool("readable") else true,
            intro = structure?.jstr("intro").orEmpty(),
            body = structure?.jstr("body").orEmpty(),
            conclusion = structure?.jstr("conclusion").orEmpty(),
            coverage = o.jstr("content_coverage"),
            examples = o.jstr("examples_data"),
            words = length?.jint("words"),
            limit = length?.jint("limit"),
            lengthComment = length?.jstr("comment").orEmpty(),
            presentation = o.jstr("presentation"),
            strengths = o.jlist("strengths"),
            improvements = o.jlist("improvements"),
            outline = o.jlist("model_outline"),
            transcript = o.jstr("transcript"),
        )
    }

    /** A sensible writing time: about one minute for every 17 words, between 5 and 60 minutes. */
    fun suggestedMinutes(wordLimit: Int): Int = ((wordLimit + 16) / 17).coerceIn(5, 60)

    /** Kind of question for a word limit: short (150 or less), mains, essay (800 or more). */
    fun kindForLimit(limit: Int): String = when {
        limit <= 150 -> "short"
        limit >= 800 -> "essay"
        else -> "mains"
    }

    fun kindLabel(kind: String): String = when (kind) {
        "short" -> "Short answer"
        "essay" -> "Essay"
        else -> "Mains answer"
    }

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun canSendTyped(text: String): Boolean = wordCount(text) >= MIN_TYPED_WORDS

    /** Time left as "12:05"; after the time is up it shows "+2:10" (minutes over). */
    fun clock(secondsLeft: Int): String {
        val abs = Math.abs(secondsLeft)
        val text = "%d:%02d".format(abs / 60, abs % 60)
        return if (secondsLeft < 0) "+$text" else text
    }

    fun scoreLabel(score: Double?): String = if (score == null) "-" else "%.1f / 10".format(java.util.Locale.ENGLISH, score)

    /** 1 good, 2 middle, 3 low, for the colour of a score. */
    fun scoreTone(score: Double?): Int = when {
        score == null -> 2
        score >= 7.0 -> 1
        score >= 4.0 -> 2
        else -> 3
    }

    /** 1 when the answer is within about 10 percent of the limit, 2 when short, 3 when much too long. */
    fun lengthTone(words: Int, limit: Int): Int = when {
        limit <= 0 -> 2
        words > limit * 1.15 -> 3
        words >= limit * 0.8 -> 1
        else -> 2
    }

    /** Heights (0 to 1) for the little score chart; the oldest score first. */
    fun trend(scores: List<Double>): List<Float> = scores.map { (it / 10.0).coerceIn(0.05, 1.0).toFloat() }
}

package com.naveen.civilscompanion.ui.telugu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class VocabCard(val te: String, val roman: String, val en: String, val exTe: String, val exEn: String)

data class PassageQuestion(val qTe: String, val qEn: String, val options: List<String>, val answer: Int)

data class Passage(
    val title: String,
    val titleEn: String,
    val textTe: String,
    val textEn: String,
    val questions: List<PassageQuestion>,
)

data class WordHint(val te: String, val en: String)

data class TranslationTask(
    val direction: String,
    val en: String,
    val te: String,
    val reference: String,
    val hints: List<WordHint>,
) {
    val toTelugu: Boolean get() = direction != "te_to_en"
}

data class WritingTemplate(
    val titleEn: String,
    val titleTe: String,
    val form: String,
    val taskEn: String,
    val minWords: Int,
    val structure: List<WordHint>,
    val phrases: List<WordHint>,
    val sampleTe: String,
)

data class Correction(val said: String, val better: String, val why: String)

data class TeluguFeedback(
    val score: Double,
    val summary: String,
    val strengths: List<String>,
    val corrections: List<Correction>,
    val modelAnswer: String,
)

/** Reads the `content_json` of a TeluguItem (see the yaml files in data/telugu) and a `telugu_feedback` job result. Forgiving: missing = empty. */
object TeluguContent {
    private fun JsonObject.s(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }

    private fun pairs(list: List<JsonObject>): List<WordHint> = list.map { WordHint(it.s("te"), it.s("en")) }

    fun vocab(o: JsonObject) = VocabCard(o.s("te"), o.s("roman"), o.s("en"), o.s("ex_te"), o.s("ex_en"))

    fun passage(o: JsonObject) = Passage(
        title = o.s("title"),
        titleEn = o.s("title_en"),
        textTe = o.s("text_te"),
        textEn = o.s("text_en"),
        questions = o.objects("questions").map { q ->
            PassageQuestion(
                q.s("q_te"), q.s("q_en"), q.strings("options"),
                (q["answer"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.filter { it.options.isNotEmpty() && it.answer in it.options.indices },
    )

    fun translation(o: JsonObject) = TranslationTask(
        o.s("direction").ifBlank { "en_to_te" }, o.s("en"), o.s("te"), o.s("reference"), pairs(o.objects("hint_words")),
    )

    fun template(o: JsonObject) = WritingTemplate(
        titleEn = o.s("title_en"),
        titleTe = o.s("title_te"),
        form = o.s("form"),
        taskEn = o.s("task_en"),
        minWords = (o["min_words"] as? JsonPrimitive)?.intOrNull ?: 100,
        structure = pairs(o.objects("structure")),
        phrases = pairs(o.objects("phrases")),
        sampleTe = o.s("sample_te"),
    )

    /** True for practice that is only general (not checked against the official syllabus). */
    fun isGeneral(o: JsonObject): Boolean = (o["official"] as? JsonPrimitive)?.content != "true"

    /** The result of a `telugu_feedback` job: {item_id, score (0 to 10), feedback: {summary, strengths[], corrections[], model_answer}}. */
    fun feedback(result: JsonObject?): TeluguFeedback? {
        val r = result ?: return null
        val fb = r.obj("feedback") ?: return null
        val score = (r["score"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        return TeluguFeedback(
            score = score.coerceIn(0.0, 10.0),
            summary = fb.s("summary"),
            strengths = fb.strings("strengths"),
            corrections = fb.objects("corrections").map { Correction(it.s("said"), it.s("better"), it.s("why")) },
            modelAnswer = fb.s("model_answer"),
        )
    }

    /** Words in a text, for the word counter while writing (Telugu and English alike). */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}

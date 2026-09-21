package com.naveen.civilscompanion.ui.answers

import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One practice prompt for a descriptive paper (SI Final Paper I and II, Group-I Mains English, Telugu and General Essay). */
@Serializable
data class WritingPrompt(
    val key: String = "",
    /** SI, APPSC or BOTH. */
    val exam: String = "BOTH",
    /** en or te. */
    val language: String = "en",
    val paper: String = "",
    val form: String = "",
    val marks: Int = 0,
    @SerialName("word_limit") val wordLimit: Int = 250,
    val prompt: String = "",
    val source: String = "",
)

@Serializable
internal data class WritingPromptFile(val items: List<WritingPrompt> = emptyList())

/** The practice prompt bank (assets/writing_prompts.json). Plain Kotlin: parsing and filtering only, unit tested. */
object WritingPrompts {
    const val ASSET_NAME = "writing_prompts.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the file text. Anything unreadable gives an empty list; entries without a key or a prompt are dropped. */
    fun parse(text: String): List<WritingPrompt> = try {
        json.decodeFromString(WritingPromptFile.serializer(), text).items
            .filter { it.key.isNotBlank() && it.prompt.isNotBlank() }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** exam null = all. "SI" keeps SI and BOTH prompts, "APPSC" keeps APPSC and BOTH. language "en" or "te". */
    fun filter(all: List<WritingPrompt>, exam: String?, language: String): List<WritingPrompt> =
        all.filter { p ->
            p.language == language && (exam == null || p.exam == exam || p.exam == "BOTH")
        }

    /** "Essay, 200 words" for a list row. */
    fun rowLabel(p: WritingPrompt): String {
        val form = p.form.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return if (p.wordLimit > 0) "$form, ${p.wordLimit} words" else form
    }

    /** The first line or so of the prompt, shortened for a list row. */
    fun preview(p: WritingPrompt, maxChars: Int = 110): String {
        val flat = p.prompt.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= maxChars) flat else flat.substring(0, maxChars).trimEnd() + "..."
    }
}

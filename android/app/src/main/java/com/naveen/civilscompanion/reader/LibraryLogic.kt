package com.naveen.civilscompanion.reader

/** Plain rules for the Library screen (no Android), so they can be unit tested. */

/** How a document's server status is shown: [tone] is the Pill tone (0 neutral, 1 green, 2 amber, 3 red). */
data class StatusLook(val label: String, val tone: Int, val busy: Boolean)

fun statusLook(status: String, detail: String): StatusLook = when (status) {
    "processed" -> StatusLook(detail.ifBlank { "Processed · searchable" }, 1, false)
    "waiting" -> StatusLook(detail.ifBlank { "Waiting for internet" }, 2, true)
    "needs_ocr" -> StatusLook(detail.ifBlank { "Needs OCR" }, 2, false)
    "failed" -> StatusLook(detail.ifBlank { "Could not be read" }, 3, false)
    "downloading" -> StatusLook(detail.ifBlank { "Downloading" }, 2, true)
    "uploaded", "processing" -> StatusLook(detail.ifBlank { "Reading the file" }, 2, true)
    else -> StatusLook(detail.ifBlank { status }, 0, false)
}

fun typeLabel(type: String): String = when (type) {
    "pdf" -> "PDF"
    "image" -> "Photos"
    "scan" -> "Scan"
    "recommended" -> "Recommended"
    "note" -> "Note"
    else -> type.replaceFirstChar { it.uppercase() }
}

const val NO_SUBJECT = "Not sorted yet"

/** Groups items by subject (alphabetical, "Not sorted yet" last). Keeps the order of items inside each group. */
fun <T> groupBySubject(items: List<T>, subjectOf: (T) -> String): List<Pair<String, List<T>>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        val subject = subjectOf(item).ifBlank { NO_SUBJECT }
        groups.getOrPut(subject) { ArrayList() }.add(item)
    }
    return groups.entries
        .sortedWith(compareBy({ it.key == NO_SUBJECT }, { it.key.lowercase() }))
        .map { it.key to it.value.toList() }
}

/** True when every word typed in [query] appears in [title] (case ignored). An empty query matches everything. */
fun titleMatches(title: String, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val t = title.lowercase()
    return words.all { t.contains(it) }
}

/** Short piece of [text] around the first place [query] appears, for search results. */
fun snippetAround(text: String, query: String, radius: Int = 70): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val word = query.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() } ?: return flat.take(radius * 2)
    val at = flat.indexOf(word, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val from = (at - radius).coerceAtLeast(0)
    val to = (at + word.length + radius).coerceAtMost(flat.length)
    return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
}

/** True when a good share of the letters in [text] are Telugu (so the Telugu font and voice are used). */
fun looksTelugu(text: String): Boolean {
    var telugu = 0
    var letters = 0
    for (ch in text) {
        if (ch in 'ఀ'..'౿') {
            telugu++
            letters++
        } else if (ch.isLetter()) {
            letters++
        }
    }
    return telugu >= 3 && letters > 0 && telugu * 10 >= letters * 4
}

/** Sends a page's text to the tutor as a starting question (kept short so the input box stays readable). */
fun askAboutPageText(pageText: String, page: Int, title: String): String {
    val flat = pageText.replace(Regex("\\s+"), " ").trim().take(600)
    return "Explain this from \"$title\", page $page, in simple words: $flat"
}

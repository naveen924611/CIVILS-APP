package com.naveen.civilscompanion.reader

/** One sentence of a page. [start] and [end] are positions in the original text (text.substring(start, end) == text). */
data class Sentence(val text: String, val start: Int, val end: Int)

/**
 * Cuts page text into sentences for reading aloud and highlighting. Plain Kotlin (no Android), so it is unit tested.
 * It does not cut after short forms such as "Art." or "No.", inside numbers such as 3.5, or before a lower-case word.
 * A blank line always ends a sentence. Very long sentences are cut at a comma or a space.
 */
object SentenceSplitter {
    const val MAX_LENGTH = 420

    private const val STOPS = ".?!।" // । is the danda used in some Telugu and Hindi text
    private const val CLOSERS = "\"'”’)]"

    private val shortForms = setOf(
        "art", "arts", "no", "nos", "dr", "mr", "mrs", "ms", "st", "vs", "sec", "cl", "ch", "fig", "vol", "p", "pp",
        "rs", "ed", "eds", "govt", "dept", "approx", "viz", "cf", "prof", "sri", "smt", "jan", "feb", "mar", "apr",
        "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "e.g", "i.e", "a.m", "p.m", "para", "s",
    )

    fun split(text: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        val n = text.length
        var start = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                var j = i + 1
                while (j < n && (text[j] == ' ' || text[j] == '\t' || text[j] == '\r')) j++
                if (j < n && text[j] == '\n') {
                    emit(out, text, start, i)
                    start = j
                    i = j + 1
                    continue
                }
            }
            if (STOPS.indexOf(c) >= 0) {
                var k = i
                while (k + 1 < n && STOPS.indexOf(text[k + 1]) >= 0) k++
                while (k + 1 < n && CLOSERS.indexOf(text[k + 1]) >= 0) k++
                val atEnd = k + 1 >= n
                if (atEnd || (text[k + 1].isWhitespace() && isBoundary(text, i, k))) {
                    emit(out, text, start, k + 1)
                    start = k + 1
                }
                i = k + 1
                continue
            }
            i++
        }
        emit(out, text, start, n)
        return out
    }

    /** Index of the sentence that contains text position [offset] (the nearest one when it falls in a gap). */
    fun indexAt(sentences: List<Sentence>, offset: Int): Int {
        if (sentences.isEmpty()) return -1
        for ((index, s) in sentences.withIndex()) {
            if (offset < s.end) return index
        }
        return sentences.lastIndex
    }

    private fun isBoundary(text: String, dot: Int, last: Int): Boolean {
        if (text[dot] == '.' && last == dot) {
            var b = dot
            while (b > 0 && (text[b - 1].isLetter() || text[b - 1] == '.')) b--
            val word = text.substring(b, dot).lowercase()
            if (word in shortForms) return false
            if (word.length == 1 && word[0].isLetter() && text[b].isUpperCase()) return false // an initial such as "M."
        }
        var j = last + 1
        while (j < text.length && text[j].isWhitespace()) j++
        if (j < text.length && text[dot] == '.' && text[j].isLowerCase()) return false
        return true
    }

    private fun emit(out: MutableList<Sentence>, text: String, from: Int, to: Int) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e <= s) return
        while (e - s > MAX_LENGTH) {
            var cut = text.lastIndexOf(' ', s + MAX_LENGTH)
            val comma = maxOf(text.lastIndexOf(", ", s + MAX_LENGTH), text.lastIndexOf("; ", s + MAX_LENGTH))
            if (comma > s + MAX_LENGTH / 2) cut = comma + 1
            if (cut <= s) cut = s + MAX_LENGTH
            out.add(Sentence(text.substring(s, cut), s, cut))
            s = cut
            while (s < e && text[s].isWhitespace()) s++
        }
        if (e > s) out.add(Sentence(text.substring(s, e), s, e))
    }
}

/** A paragraph of a page: text.substring(start, end). */
data class Paragraph(val start: Int, val end: Int)

private val paragraphBreak = Regex("\n[ \t\r]*\n[\\s]*")

/** Cuts page text at blank lines. Blank paragraphs are skipped. */
fun paragraphsOf(text: String): List<Paragraph> {
    val out = ArrayList<Paragraph>()
    var from = 0
    for (m in paragraphBreak.findAll(text)) {
        addParagraph(out, text, from, m.range.first)
        from = m.range.last + 1
    }
    addParagraph(out, text, from, text.length)
    return out
}

private fun addParagraph(out: MutableList<Paragraph>, text: String, from: Int, to: Int) {
    var s = from
    var e = to
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (e > s) out.add(Paragraph(s, e))
}

/** Index of the paragraph that holds text position [offset] (the nearest one when it falls in a gap), or -1. */
fun paragraphIndexAt(paragraphs: List<Paragraph>, offset: Int): Int {
    if (paragraphs.isEmpty()) return -1
    for ((i, p) in paragraphs.withIndex()) if (offset < p.end) return i
    return paragraphs.lastIndex
}

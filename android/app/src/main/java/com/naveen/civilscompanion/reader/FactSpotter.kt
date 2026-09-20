package com.naveen.civilscompanion.reader

enum class FactKind(val label: String) {
    Article("Articles"),
    Amendment("Amendments"),
    Date("Dates and years"),
    Number("Numbers"),
    Body("Committees and commissions"),
}

/** Something worth remembering, found in a page of text. [sentence] is the sentence it came from. */
data class Fact(val kind: FactKind, val text: String, val sentence: String)

/**
 * On-device fact spotting for scanned pages (spec 7.3): Article numbers, amendments, dates and years,
 * numbers with units, and committee or commission names. Uses only regular expressions, so it works offline.
 */
object FactSpotter {
    private const val MONTH =
        "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
    private const val ORD = "(?:st|nd|rd|th)"

    private val article = Regex("""\b(?:Article|Art)s?\.?\s?\d{1,3}[A-Z]{0,2}(?:\(\d+\))?(?:\([a-z]\))?""")
    private val amendment = listOf(
        Regex("""\bConstitution\s*\(\s*\d{1,3}$ORD\s+Amendment\s*\)\s*Act(?:,?\s*(?:19|20)\d{2})?"""),
        Regex("""\b\d{1,3}$ORD\s+(?:Constitutional\s+)?Amendment(?:\s+Act)?(?:,?\s+(?:19|20)\d{2})?"""),
    )
    private val fullDate = listOf(
        Regex("""\b\d{1,2}$ORD?\s+$MONTH\.?,?\s+\d{4}\b"""),
        Regex("""\b$MONTH\.?\s+\d{1,2}$ORD?,?\s+\d{4}\b"""),
    )
    private val year = Regex("""\b(?:1[5-9]\d{2}|20\d{2})\b""")
    private val number = Regex(
        """\b\d[\d,]*(?:\.\d+)?\s?(?:(?:per\s?cent|crore|lakh|million|billion|trillion|thousand|sq\.?\s?km|km|hectares?|tonnes?|MW|GW|kg|metres?|years?|members?|seats?|districts?|states?)\b|%)""",
    )
    private val body = Regex("""\b(?:[A-Z][A-Za-z]+\s+){1,4}(?:Committee|Commission|Council|Tribunal|Board|Authority)\b""")

    fun spot(text: String, maxPerKind: Int = 12): List<Fact> {
        if (text.isBlank()) return emptyList()
        val sentences = SentenceSplitter.split(text)
        val covered = ArrayList<IntRange>()
        val found = ArrayList<Triple<Int, FactKind, String>>()

        fun take(kind: FactKind, regex: Regex, clean: (String) -> String = { it }) {
            for (m in regex.findAll(text)) {
                if (covered.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                val value = clean(m.value.trim()).trim()
                if (value.isEmpty()) continue
                covered.add(m.range)
                found.add(Triple(m.range.first, kind, value))
            }
        }

        take(FactKind.Article, article)
        amendment.forEach { take(FactKind.Amendment, it) }
        fullDate.forEach { take(FactKind.Date, it) }
        take(FactKind.Number, number)
        take(FactKind.Date, year)
        take(FactKind.Body, body) { it.removePrefix("The ").removePrefix("This ").removePrefix("A ") }

        val seen = HashSet<String>()
        val perKind = HashMap<FactKind, Int>()
        val out = ArrayList<Fact>()
        for ((pos, kind, value) in found.sortedBy { it.first }) {
            if (!seen.add(kind.name + "|" + value.lowercase())) continue
            val count = perKind.getOrDefault(kind, 0)
            if (count >= maxPerKind) continue
            perKind[kind] = count + 1
            val index = SentenceSplitter.indexAt(sentences, pos)
            out.add(Fact(kind, value, if (index >= 0) sentences[index].text else value))
        }
        return out
    }
}

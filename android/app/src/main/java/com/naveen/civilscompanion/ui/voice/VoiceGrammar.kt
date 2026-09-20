package com.naveen.civilscompanion.ui.voice

/** What the owner can say, even offline (spec 7.7). Matching is done on the tablet, no AI involved. */
sealed interface VoiceCommand {
    data object ReadBrief : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data object Repeat : VoiceCommand
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Faster : VoiceCommand
    data object Slower : VoiceCommand
    data object WhatsNext : VoiceCommand
    data object StartRevision : VoiceCommand
    data object MarkDone : VoiceCommand
    data object ReadPage : VoiceCommand
    data object SaveThis : VoiceCommand
    data class SetTimer(val minutes: Int) : VoiceCommand
}

/** Pure text matching (no Android classes) so it can be tested on a computer. */
object VoiceGrammar {
    /** Phrases shown as "You can say, even offline". */
    val EXAMPLES: List<String> = listOf(
        "Read today's brief", "What's next?", "Start revision", "Read this page", "Mark done", "Pause",
    )

    private val fillers = Regex("^(hey|ok|okay|please|now|can you|could you|will you)\\s+")
    private val trailingFillers = Regex("\\s+(please|now|for me)$")

    private val readBrief = Regex("^(read|play|start)( me)?( the| my| todays| today)*( morning| evening)? (brief|briefs|briefing|news)$")
    private val next = Regex("^(next|skip|next one|next item|go next|skip this|skip it)$")
    private val previous = Regex("^(previous|back|go back|previous one|previous item|last one)$")
    private val repeat = Regex("^(repeat|repeat that|say again|say that again|read again|read that again|once more)$")
    private val pause = Regex("^(pause|stop|stop reading|hold on|quiet|be quiet|silence|wait)$")
    private val resume = Regex("^(resume|continue|carry on|go on|keep going|play|start reading)$")
    private val faster = Regex("^(faster|speed up|read faster|talk faster|go faster|speak faster)$")
    private val slower = Regex("^(slower|slow down|read slower|talk slower|go slower|speak slower)$")
    private val whatsNext = Regex("^(whats next|what is next|what next|what should i do( next)?|whats my next task|what do i do next|whats my next block)$")
    private val startRevision = Regex("^(start|begin|open|do)( my| the)? (revision|revise|review|cards|flashcards)( session| now)?$|^revise$|^revise now$")
    private val markDone = Regex("^(mark( it| this| that)?( as)? (done|complete|completed)|done|im done|i am done|finished|task done|mark done)$")
    private val readPage = Regex("^(read|listen to)( me)?( this| the| current| my)? page( aloud| out loud)?$|^read this$|^read aloud$")
    private val saveThis = Regex("^(save|bookmark|highlight|remember)( this| that| it)?( sentence| page| point| line)?$")
    private val timerA = Regex("^(set|start)( a| the)? timer( for)? (\\w+( \\w+)?) (minutes?|mins?)$")
    private val timerB = Regex("^(set|start)( a)? (\\w+( \\w+)?) (minutes?|mins?) timer$")
    private val timerC = Regex("^timer (\\w+( \\w+)?) (minutes?|mins?)$")

    private val small = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fortyfive" to 45, "sixty" to 60, "ninety" to 90,
    )

    /** Lower case, no punctuation, no filler words. "Hey, what's next?" becomes "whats next". */
    fun normalise(text: String): String {
        var t = text.lowercase().replace("'", "").replace("’", "")
        t = t.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        var before: String
        do {
            before = t
            t = t.replace(fillers, "").replace(trailingFillers, "").trim()
        } while (t != before)
        return t
    }

    /** "ten", "twenty five", "15" -> number; null when it is not a number we understand. */
    fun numberOf(word: String): Int? {
        val w = word.trim().lowercase()
        w.toIntOrNull()?.let { return it }
        small[w]?.let { return it }
        val parts = w.split(' ')
        if (parts.size == 2) {
            val tens = small[parts[0]]
            val ones = small[parts[1]]
            if (tens != null && ones != null && tens in listOf(20, 30, 40, 60) && ones in 1..9) return tens + ones
        }
        return null
    }

    private fun timer(m: MatchResult?, group: Int): VoiceCommand.SetTimer? {
        val minutes = m?.groupValues?.get(group)?.let { numberOf(it) } ?: return null
        return if (minutes in 1..600) VoiceCommand.SetTimer(minutes) else null
    }

    /** Returns the command the whole sentence means, or null when it is not a command (then it is a question). */
    fun parse(text: String): VoiceCommand? {
        val t = normalise(text)
        if (t.isEmpty()) return null
        return when {
            readBrief.matches(t) -> VoiceCommand.ReadBrief
            next.matches(t) -> VoiceCommand.Next
            previous.matches(t) -> VoiceCommand.Previous
            repeat.matches(t) -> VoiceCommand.Repeat
            pause.matches(t) -> VoiceCommand.Pause
            resume.matches(t) -> VoiceCommand.Resume
            faster.matches(t) -> VoiceCommand.Faster
            slower.matches(t) -> VoiceCommand.Slower
            whatsNext.matches(t) -> VoiceCommand.WhatsNext
            startRevision.matches(t) -> VoiceCommand.StartRevision
            markDone.matches(t) -> VoiceCommand.MarkDone
            readPage.matches(t) -> VoiceCommand.ReadPage
            saveThis.matches(t) -> VoiceCommand.SaveThis
            timerA.matches(t) -> timer(timerA.matchEntire(t), 4)
            timerB.matches(t) -> timer(timerB.matchEntire(t), 3)
            timerC.matches(t) -> timer(timerC.matchEntire(t), 1)
            else -> null
        }
    }

    /**
     * Hands-free revision (spec 7.7): the owner says how well he remembered.
     * Returns 1 again, 2 hard, 3 good, 4 easy, or null.
     */
    fun parseGrade(text: String): Int? = when (normalise(text)) {
        "again", "repeat", "forgot", "i forgot", "no", "wrong" -> 1
        "hard", "difficult", "tough" -> 2
        "good", "ok", "correct", "right", "yes", "got it" -> 3
        "easy", "very easy", "too easy" -> 4
        else -> null
    }
}

/** Short kind words for the message shown after a command ("Starting revision"). */
fun VoiceCommand.doneText(): String = when (this) {
    VoiceCommand.ReadBrief -> "Reading today's brief"
    VoiceCommand.Next -> "Next"
    VoiceCommand.Previous -> "Going back"
    VoiceCommand.Repeat -> "Repeating"
    VoiceCommand.Pause -> "Paused"
    VoiceCommand.Resume -> "Continuing"
    VoiceCommand.Faster -> "Faster"
    VoiceCommand.Slower -> "Slower"
    VoiceCommand.WhatsNext -> "Checking your plan"
    VoiceCommand.StartRevision -> "Starting revision"
    VoiceCommand.MarkDone -> "Marking done"
    VoiceCommand.ReadPage -> "Reading this page"
    VoiceCommand.SaveThis -> "Saving this"
    is VoiceCommand.SetTimer -> "Timer set for $minutes minutes"
}

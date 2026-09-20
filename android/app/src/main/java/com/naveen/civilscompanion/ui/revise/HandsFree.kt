package com.naveen.civilscompanion.ui.revise

/** Words the owner says in hands-free revision (spec 6.5): the app reads the card, he answers aloud, then says the grade. */
object HandsFree {
    private val reveal = listOf("show", "answer", "reveal", "flip", "turn", "ready", "done")
    private val again = listOf("again", "repeat", "forgot", "wrong", "no")
    private val hard = listOf("hard", "difficult", "tough")
    private val good = listOf("good", "correct", "right", "yes", "okay", "ok")
    private val easy = listOf("easy", "perfect", "simple")
    private val stop = listOf("stop", "pause", "quit", "exit")

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /** 1 Again, 2 Hard, 3 Good, 4 Easy, or null when no grade word was heard. The first grade word wins. */
    fun parseGrade(text: String): Int? {
        for (w in words(text)) {
            when (w) {
                in easy -> return 4
                in hard -> return 2
                in again -> return 1
                in good -> return 3
            }
        }
        return null
    }

    fun wantsReveal(text: String): Boolean = words(text).any { it in reveal }

    fun wantsStop(text: String): Boolean = words(text).any { it in stop }
}

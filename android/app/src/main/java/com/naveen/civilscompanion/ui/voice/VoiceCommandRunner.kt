package com.naveen.civilscompanion.ui.voice

import androidx.navigation.NavHostController
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.NavEvents
import com.naveen.civilscompanion.PendingAction
import com.naveen.civilscompanion.data.local.BriefDao
import com.naveen.civilscompanion.data.model.DailyPlan
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.playback.PlayerConnection
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.ui.ask.AskLogic
import com.naveen.civilscompanion.ui.ask.AskOutcome
import com.naveen.civilscompanion.ui.ask.AskRepository
import com.naveen.civilscompanion.ui.nav.Routes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface VoiceOutcome {
    val message: String

    /** A command was carried out. */
    data class Command(override val message: String) : VoiceOutcome

    /** A question was answered from the notes on the tablet. */
    data class OfflineAnswer(override val message: String) : VoiceOutcome

    /** A question was saved for the tutor. */
    data class Queued(override val message: String) : VoiceOutcome
}

/**
 * Carries out what the owner said (spec 7.7): a fixed command works offline; anything else is a question
 * (notes on the tablet first, then the tutor queue). Used by the floating mic and the Ask screen.
 */
@Singleton
class VoiceCommandRunner @Inject constructor(
    private val ask: AskRepository,
    private val store: RecordStore,
    private val player: PlayerConnection,
    private val tts: TtsSpeaker,
    private val settings: VoiceSettings,
    private val bus: VoiceBus,
    private val timer: VoiceTimer,
    private val navEvents: NavEvents,
    private val briefs: BriefDao,
) {
    private var lastSpoken: String = ""

    /** Speaks a text with the chosen voice and speed, and remembers it for "repeat". */
    fun say(text: String) {
        lastSpoken = text
        speak(text)
    }

    private fun speak(text: String) {
        val language = if (AskLogic.isTelugu(text)) "te-IN" else settings.voiceName()
        tts.speak(text, language = language, rate = settings.speed())
    }

    suspend fun handle(text: String, nav: NavHostController, topicId: String? = null): VoiceOutcome {
        val command = VoiceGrammar.parse(text)
        if (command != null) {
            val message = if (bus.dispatch(command)) command.doneText() else execute(command, nav)
            return VoiceOutcome.Command(message)
        }
        return when (val result = ask.ask(text, via = "voice", topicId = topicId)) {
            is AskOutcome.Offline -> {
                say(AskLogic.plainForSpeech(result.text))
                VoiceOutcome.OfflineAnswer("Answered offline from your notes (${result.topicTitle}).")
            }
            AskOutcome.Queued -> VoiceOutcome.Queued("Saved your question. I will answer when the internet is back.")
        }
    }

    private suspend fun execute(command: VoiceCommand, nav: NavHostController): String = when (command) {
        VoiceCommand.ReadBrief -> readBrief(nav)
        VoiceCommand.Next -> {
            if (player.state.value.hasQueue) player.next()
            command.doneText()
        }
        VoiceCommand.Previous -> {
            if (player.state.value.hasQueue) player.previous()
            command.doneText()
        }
        VoiceCommand.Repeat -> repeatLast()
        VoiceCommand.Pause -> pause()
        VoiceCommand.Resume -> resume()
        VoiceCommand.Faster -> changeSpeed(faster = true)
        VoiceCommand.Slower -> changeSpeed(faster = false)
        VoiceCommand.WhatsNext -> whatsNext()
        VoiceCommand.StartRevision -> {
            nav.navigate(Routes.REVISE_SESSION)
            command.doneText()
        }
        VoiceCommand.MarkDone -> markDone()
        VoiceCommand.ReadPage -> {
            nav.navigate(Routes.READ) { launchSingleTop = true }
            "Open a page in Read, then say \"read this page\" again."
        }
        VoiceCommand.SaveThis -> "There is nothing to save on this screen."
        is VoiceCommand.SetTimer -> {
            timer.start(command.minutes)
            command.doneText()
        }
    }

    // ------------------------------------------------------------------ audio

    private suspend fun readBrief(nav: NavHostController): String {
        val startOfToday = TimeUtil.startOfDay(TimeUtil.today())
        val ready = briefs.readySince(startOfToday).firstOrNull() ?: briefs.readySince(0L).firstOrNull()
        nav.navigate(Routes.BRIEFS) {
            popUpTo(Routes.TODAY) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        if (ready == null) return "There is no brief ready yet."
        navEvents.post(PendingAction(AppLinks.ACTION_PLAY_BRIEF, briefId = ready.id))
        return VoiceCommand.ReadBrief.doneText()
    }

    private fun repeatLast(): String {
        if (lastSpoken.isNotEmpty() && !player.state.value.hasQueue) {
            speak(lastSpoken)
            return "Repeating"
        }
        if (player.state.value.hasQueue) {
            player.seekBy(-3_600_000L) // back to the start of this item
            return "Repeating"
        }
        return "There is nothing to repeat."
    }

    private fun pause(): String {
        if (tts.speaking.value) {
            tts.stop()
            return "Paused"
        }
        if (player.state.value.isPlaying) player.toggle()
        return "Paused"
    }

    private fun resume(): String {
        val state = player.state.value
        if (state.hasQueue && !state.isPlaying) {
            player.toggle()
            return "Continuing"
        }
        if (lastSpoken.isNotEmpty() && !tts.speaking.value) {
            speak(lastSpoken)
            return "Continuing"
        }
        return "Nothing to continue."
    }

    private suspend fun changeSpeed(faster: Boolean): String {
        val next = VoiceLogic.nextSpeed(settings.speed(), faster)
        settings.setSpeed(next)
        if (player.state.value.hasQueue) player.setSpeed(next)
        return "Speed ${VoiceLogic.formatSpeed(next)}"
    }

    // ------------------------------------------------------------------ today's plan

    private suspend fun todayPlan(): DailyPlan? =
        store.list(Tables.DailyPlans, RecordQuery(k1 = TimeUtil.today())).firstOrNull()

    private suspend fun whatsNext(): String {
        val plan = todayPlan()
        val step = plan?.let { VoiceLogic.nextStep(it) }
        val message = when {
            plan == null || plan.blocks.isEmpty() -> "You have no plan for today yet. Open Today to make one."
            step == null -> "You have finished everything planned for today. Well done."
            else -> "Next: ${step.title}, at ${step.start}, for ${step.minutes} minutes."
        }
        say(message)
        return message
    }

    private suspend fun markDone(): String {
        val plan = todayPlan() ?: return "There is no plan for today yet."
        val step = VoiceLogic.nextStep(plan) ?: return "Everything planned for today is already done."
        store.update(Tables.DailyPlans, plan.id) {
            it.copy(completion = JsonObject(it.completion + (step.id to JsonPrimitive("done"))))
        }
        return "Marked done: ${step.title}"
    }
}

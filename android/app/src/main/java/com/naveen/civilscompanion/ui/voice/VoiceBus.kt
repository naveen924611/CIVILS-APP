package com.naveen.civilscompanion.ui.voice

import com.naveen.civilscompanion.speech.TtsSpeaker
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lets a screen take over a voice command. For example the Reader can handle "read this page" and Revise can
 * handle "next" or "mark done": in a DisposableEffect call `voiceBus.register { cmd -> ... true when handled }`
 * and call the returned function when the screen leaves. The newest screen is asked first. Commands nobody
 * handles are done by the global runner (VoiceCommandRunner).
 */
@Singleton
class VoiceBus @Inject constructor() {
    private val handlers = CopyOnWriteArrayList<(VoiceCommand) -> Boolean>()

    /** Returns a function that removes the handler again. */
    fun register(handler: (VoiceCommand) -> Boolean): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    /** True when a screen handled the command. Call on the main thread. */
    fun dispatch(command: VoiceCommand): Boolean {
        for (handler in handlers.toList().asReversed()) { // asReversed: List.reversed() is a JDK 21 method that older tablets lack
            if (handler(command)) return true
        }
        return false
    }
}

/** "Set timer 10 minutes": a countdown that speaks when it ends. Lives as long as the app is running. */
@Singleton
class VoiceTimer @Inject constructor(
    private val tts: TtsSpeaker,
    private val settings: VoiceSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow(0L)

    /** Milliseconds left; 0 when no timer is running. */
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    fun start(minutes: Int) {
        job?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _remainingMs.value = minutes * 60_000L
        job = scope.launch {
            while (true) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) break
                _remainingMs.value = left
                delay(500)
            }
            _remainingMs.value = 0L
            tts.speak(
                if (minutes == 1) "Your one minute timer is done." else "Your $minutes minute timer is done.",
                language = settings.voiceName(),
                rate = settings.speed(),
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
    }
}

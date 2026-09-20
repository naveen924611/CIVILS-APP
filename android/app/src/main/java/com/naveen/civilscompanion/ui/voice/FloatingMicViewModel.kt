package com.naveen.civilscompanion.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.speech.VoiceEvent
import com.naveen.civilscompanion.speech.VoiceInput
import com.naveen.civilscompanion.ui.answers.AnswerUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MicUi(
    val listening: Boolean = false,
    val partial: String = "",
    val message: String = "",
    val canOpenAsk: Boolean = false,
)

@HiltViewModel
class FloatingMicViewModel @Inject constructor(
    private val voiceInput: VoiceInput,
    private val runner: VoiceCommandRunner,
    private val settings: VoiceSettings,
    private val tts: TtsSpeaker,
    private val timer: VoiceTimer,
    private val uploader: AnswerUploader,
) : ViewModel() {
    private val _ui = MutableStateFlow(MicUi())
    val ui: StateFlow<MicUi> = _ui.asStateFlow()
    val timerMs: StateFlow<Long> = timer.remainingMs

    private var listenJob: Job? = null
    private var clearJob: Job? = null

    init {
        // Photos of written answers that could not be sent (no internet) are retried while the app is open.
        viewModelScope.launch { uploader.runLoop() }
    }

    fun startListening(nav: NavHostController) {
        if (_ui.value.listening) return
        clearJob?.cancel()
        tts.stop()
        listenJob = viewModelScope.launch {
            _ui.value = MicUi(listening = true)
            var heard = ""
            voiceInput.listen(settings.listenLanguage(), preferOffline = true).collect { event ->
                when (event) {
                    VoiceEvent.Listening -> _ui.value = MicUi(listening = true)
                    is VoiceEvent.Partial -> _ui.value = MicUi(listening = true, partial = event.text)
                    is VoiceEvent.Final -> heard = event.text
                    is VoiceEvent.Failed -> show(event.message, false)
                }
            }
            if (heard.isNotBlank()) {
                val outcome = runner.handle(heard, nav)
                show(outcome.message, outcome !is VoiceOutcome.Command)
            } else if (_ui.value.listening) {
                show("I did not hear anything.", false)
            }
        }
    }

    fun cancel() {
        listenJob?.cancel()
        _ui.value = MicUi()
    }

    fun micDenied() = show("The microphone is off. Turn it on in the tablet's app settings.", false)

    fun dismiss() {
        clearJob?.cancel()
        _ui.value = MicUi()
    }

    fun cancelTimer() = timer.cancel()

    private fun show(message: String, canOpenAsk: Boolean) {
        _ui.value = MicUi(message = message, canOpenAsk = canOpenAsk)
        clearJob?.cancel()
        clearJob = viewModelScope.launch {
            delay(if (canOpenAsk) 9_000 else 5_000)
            _ui.value = MicUi()
        }
    }
}

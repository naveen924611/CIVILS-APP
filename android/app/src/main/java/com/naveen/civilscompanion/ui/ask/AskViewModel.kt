package com.naveen.civilscompanion.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.speech.VoiceEvent
import com.naveen.civilscompanion.speech.VoiceInput
import com.naveen.civilscompanion.ui.voice.VoiceCommandRunner
import com.naveen.civilscompanion.ui.voice.VoiceOutcome
import com.naveen.civilscompanion.ui.voice.VoiceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TopicChip(val id: String, val title: String)

data class VoiceUi(val listening: Boolean = false, val partial: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AskViewModel @Inject constructor(
    private val asker: AskRepository,
    private val jobs: JobRepository,
    private val store: RecordStore,
    private val tts: TtsSpeaker,
    private val voiceInput: VoiceInput,
    private val settings: VoiceSettings,
    private val runner: VoiceCommandRunner,
    private val draft: AskDraft,
) : ViewModel() {

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _mode = MutableStateFlow("")
    val mode: StateFlow<String> = _mode.asStateFlow()

    private val _topic = MutableStateFlow<TopicChip?>(null)
    val topic: StateFlow<TopicChip?> = _topic.asStateFlow()

    private val _draftNote = MutableStateFlow<String?>(null)
    val draftNote: StateFlow<String?> = _draftNote.asStateFlow()

    private val _voice = MutableStateFlow(VoiceUi())
    val voice: StateFlow<VoiceUi> = _voice.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private val recentJobs = jobs.observeRecent("tutor_question", 100)

    val bubbles: StateFlow<List<Bubble>> = asker.conversation.flatMapLatest { id ->
        combine(asker.observeMessages(id), recentJobs) { msgs, js -> AskBubbles.build(msgs, js) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waiting: StateFlow<List<WaitingRow>> = recentJobs.map { AskBubbles.waiting(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waitingCount: StateFlow<Int> = recentJobs.map { AskBubbles.waitingCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val history: StateFlow<List<ChatSummary>> = asker.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var listenJob: Job? = null

    private var pendingQuestions: Set<String> = emptySet()

    init {
        // An answer that arrives for a waiting question is read aloud when the owner asked for that with headphones on.
        viewModelScope.launch {
            bubbles.collect { list ->
                val arrived = list.filter { !it.fromUser && !it.offline && it.question in pendingQuestions }
                pendingQuestions = list.filter { it.fromUser && it.status.isNotEmpty() && !it.failed }.map { it.text }.toSet()
                val latest = arrived.lastOrNull()
                if (latest != null && settings.speakOnHeadphones() && settings.headphonesConnected()) toggleSpeak(latest)
            }
        }
        // "Ask about this" from another screen: show the text in the box; the owner presses send.
        viewModelScope.launch {
            draft.draft.collect { d ->
                if (d != null) {
                    _input.value = d.text
                    _draftNote.value = d.sourceLabel
                    _topic.value = d.topicId?.let { id ->
                        TopicChip(id, store.get(Tables.Topics, id)?.title ?: "This topic")
                    }
                    draft.clear()
                }
            }
        }
    }

    fun setInput(text: String) {
        _input.value = text
    }

    fun setMode(code: String) {
        _mode.value = code
    }

    fun clearTopic() {
        _topic.value = null
        _draftNote.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun send() {
        val question = _input.value.trim()
        if (question.isEmpty()) return
        _input.value = ""
        val topicId = _topic.value?.id
        val mode = _mode.value
        viewModelScope.launch {
            val outcome = asker.ask(question, via = "text", topicId = topicId, mode = mode)
            if (outcome is AskOutcome.Queued) {
                _notice.value = "Your question is saved. The answer arrives when the internet is on."
            }
        }
    }

    fun newChat() {
        tts.stop()
        _speaking.value = null
        asker.startNewConversation()
    }

    fun openChat(id: String) {
        tts.stop()
        _speaking.value = null
        asker.openConversation(id)
    }

    /** The offline answer was not enough: send the question to the tutor. */
    fun askTutorForMore(bubble: Bubble) {
        if (bubble.question.isEmpty()) return
        viewModelScope.launch {
            asker.queueForTutor(bubble.question, bubble.via, bubble.topicId ?: _topic.value?.id, _mode.value)
            _notice.value = "Asked the tutor. The full answer arrives when the internet is on."
        }
    }

    fun saveToNotes(bubble: Bubble) {
        viewModelScope.launch {
            val title = bubble.question.ifEmpty { "Tutor answer" }.take(80)
            asker.saveToNotes(AskLogic.forDisplay(bubble.text), bubble.topicId ?: _topic.value?.id, title)
            _notice.value = "Saved. It will be added to your notes when the internet is on."
        }
    }

    // ------------------------------------------------------------------ reading aloud

    fun toggleSpeak(bubble: Bubble) {
        if (_speaking.value == bubble.key) {
            tts.stop()
            _speaking.value = null
            return
        }
        val text = AskLogic.plainForSpeech(bubble.text)
        _speaking.value = bubble.key
        val language = if (AskLogic.isTelugu(text)) "te-IN" else settings.voiceName()
        tts.speakSentences(
            AskLogic.splitSentences(text),
            language = language,
            rate = settings.speed(),
            onDone = { if (_speaking.value == bubble.key) _speaking.value = null },
        )
    }

    // ------------------------------------------------------------------ voice

    fun toggleListening(nav: NavHostController) {
        if (_voice.value.listening) {
            listenJob?.cancel()
            _voice.value = VoiceUi()
            return
        }
        tts.stop()
        _speaking.value = null
        listenJob = viewModelScope.launch {
            _voice.value = VoiceUi(listening = true)
            var heard = ""
            voiceInput.listen(settings.listenLanguage(), preferOffline = true).collect { event ->
                when (event) {
                    VoiceEvent.Listening -> _voice.value = VoiceUi(listening = true)
                    is VoiceEvent.Partial -> _voice.value = VoiceUi(listening = true, partial = event.text)
                    is VoiceEvent.Final -> heard = event.text
                    is VoiceEvent.Failed -> _notice.value = event.message
                }
            }
            _voice.value = VoiceUi()
            if (heard.isNotBlank()) {
                val outcome = runner.handle(heard, nav, _topic.value?.id)
                if (outcome is VoiceOutcome.Command) _notice.value = outcome.message
            }
        }
    }

    fun micDenied() {
        _notice.value = "The microphone is off. Turn it on in the tablet's app settings."
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }
}

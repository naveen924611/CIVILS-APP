package com.naveen.civilscompanion.ui.explain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.ExplainSession
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.speech.VoiceEvent
import com.naveen.civilscompanion.speech.VoiceInput
import com.naveen.civilscompanion.ui.ask.AskLogic
import com.naveen.civilscompanion.ui.voice.VoiceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExplainForm(
    val topicId: String = "",
    val topicTitle: String = "",
    val transcript: String = "",
    val partial: String = "",
    val recording: Boolean = false,
    val seconds: Int = 0,
    val notice: String? = null,
    val speakingModel: Boolean = false,
    val cardsMade: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExplainViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val voiceInput: VoiceInput,
    private val settings: VoiceSettings,
    private val tts: TtsSpeaker,
) : ViewModel() {

    private val _form = MutableStateFlow(ExplainForm())
    val form: StateFlow<ExplainForm> = _form.asStateFlow()

    private val sessionId = MutableStateFlow<String?>(null)

    /** The explanation being followed on this screen (waiting for feedback, or with feedback). */
    val session: StateFlow<ExplainSession?> = sessionId.flatMapLatest { id ->
        if (id == null) flowOf(null) else store.observeOne(Tables.ExplainSessions, id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val topics: StateFlow<List<Topic>> = store.observe(Tables.Topics, RecordQuery(order = Order.TextAZ))
        .map { all -> all.filter { it.level >= 2 }.sortedWith(compareBy({ if (it.status == "in_progress") 0 else 1 }, { it.title })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<ExplainSession>> =
        store.observe(Tables.ExplainSessions, RecordQuery(order = Order.NewestFirst, limit = 40))
            .map { list -> list.sortedByDescending { TimeUtil.parse(it.createdAt) ?: 0L } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val topicTitles: StateFlow<Map<String, String>> = topics.map { list -> list.associate { it.id to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var recordJob: Job? = null
    private var clockJob: Job? = null

    /** Opens the recording screen for a topic (a fresh start each time). */
    fun start(topicId: String) {
        if (_form.value.topicId == topicId && (_form.value.recording || sessionId.value != null || _form.value.transcript.isNotEmpty())) return
        stopRecording()
        sessionId.value = null
        _form.value = ExplainForm(topicId = topicId)
        viewModelScope.launch {
            val title = store.get(Tables.Topics, topicId)?.title ?: "Your topic"
            _form.value = _form.value.copy(topicTitle = title)
        }
    }

    fun setTranscript(text: String) {
        _form.value = _form.value.copy(transcript = text)
    }

    fun clearNotice() {
        _form.value = _form.value.copy(notice = null)
    }

    fun micDenied() {
        _form.value = _form.value.copy(notice = "The microphone is off. Turn it on in the tablet's app settings, or type your explanation.")
    }

    // ------------------------------------------------------------------ recording

    fun startRecording() {
        if (_form.value.recording) return
        tts.stop()
        _form.value = _form.value.copy(recording = true, notice = null, partial = "", speakingModel = false)
        clockJob = viewModelScope.launch {
            while (_form.value.recording) {
                delay(1_000)
                if (_form.value.recording) _form.value = _form.value.copy(seconds = _form.value.seconds + 1)
            }
        }
        recordJob = viewModelScope.launch {
            var failures = 0
            var lastError = ""
            while (_form.value.recording) {
                var heardSomething = false
                voiceInput.listen(settings.listenLanguage(), preferOffline = true).collect { event ->
                    when (event) {
                        VoiceEvent.Listening -> lastError = ""
                        is VoiceEvent.Partial -> _form.value = _form.value.copy(partial = event.text)
                        is VoiceEvent.Final -> {
                            if (event.text.isNotBlank()) {
                                heardSomething = true
                                val old = _form.value.transcript.trim()
                                _form.value = _form.value.copy(transcript = if (old.isEmpty()) event.text else "$old ${event.text}", partial = "")
                            }
                        }
                        is VoiceEvent.Failed -> lastError = event.message
                    }
                }
                if (heardSomething) failures = 0 else failures++
                if (failures >= 3) {
                    _form.value = _form.value.copy(recording = false, partial = "", notice = lastError.ifEmpty { "I could not hear you. You can type instead." })
                }
                delay(200)
            }
        }
    }

    fun stopRecording() {
        recordJob?.cancel()
        clockJob?.cancel()
        _form.value = _form.value.copy(recording = false, partial = "")
    }

    // ------------------------------------------------------------------ sending and feedback

    fun submit() {
        val f = _form.value
        stopRecording()
        val text = f.transcript.trim()
        if (!ExplainLogic.canSubmit(text)) {
            _form.value = _form.value.copy(notice = "Please say or type a little more (at least ${ExplainLogic.MIN_WORDS} words).")
            return
        }
        viewModelScope.launch {
            val id = TimeUtil.newId()
            store.save(
                Tables.ExplainSessions,
                ExplainSession(
                    id = id, topicId = f.topicId.ifEmpty { null }, transcript = text, durationSec = f.seconds,
                    status = "queued", createdAt = TimeUtil.nowIso(),
                ),
            )
            jobs.enqueue("explain_feedback", jobPayload("session_id" to id))
            sessionId.value = id
        }
    }

    /** Opens a past explanation from the history list. */
    fun open(id: String) {
        stopRecording()
        sessionId.value = id
        _form.value = ExplainForm(topicId = "", cardsMade = 0)
        viewModelScope.launch {
            val s = store.get(Tables.ExplainSessions, id) ?: return@launch
            val title = s.topicId?.let { store.get(Tables.Topics, it)?.title } ?: "Your topic"
            _form.value = ExplainForm(topicId = s.topicId.orEmpty(), topicTitle = title, transcript = s.transcript, seconds = s.durationSec)
        }
    }

    /** "Try again": a clean recording screen for the same topic. */
    fun tryAgain() {
        tts.stop()
        val f = _form.value
        sessionId.value = null
        _form.value = ExplainForm(topicId = f.topicId, topicTitle = f.topicTitle)
    }

    /** Sends a session that failed (for example "too short") again after the owner changed the text. */
    fun editAndRetry() = tryAgain()

    fun toggleModel(text: String) {
        if (_form.value.speakingModel) {
            tts.stop()
            _form.value = _form.value.copy(speakingModel = false)
            return
        }
        _form.value = _form.value.copy(speakingModel = true)
        val language = if (AskLogic.isTelugu(text)) "te-IN" else settings.voiceName()
        tts.speakSentences(
            AskLogic.splitSentences(text), language = language, rate = settings.speed(),
            onDone = { _form.value = _form.value.copy(speakingModel = false) },
        )
    }

    /** Makes a fill-in-the-blank revision card from every point the owner missed. */
    fun makeCards(session: ExplainSession, missed: List<String>) {
        if (missed.isEmpty()) return
        viewModelScope.launch {
            val existing = store.list(Tables.Cards, RecordQuery(k2 = session.id))
            if (existing.isNotEmpty()) {
                _form.value = _form.value.copy(cardsMade = existing.size, notice = "The cards for this explanation were already made.")
                return@launch
            }
            val subject = subjectName(session.topicId)
            val topicTitle = session.topicId?.let { store.get(Tables.Topics, it)?.title } ?: subject
            val now = TimeUtil.nowIso()
            val cards = missed.map { point ->
                val (front, back) = ExplainLogic.cloze(point, topicTitle)
                Card(
                    id = TimeUtil.newId(), front = front, back = back, topicId = session.topicId, sourceType = "explain",
                    sourceId = session.id, group = subject, fsrsState = null, dueAt = now,
                )
            }
            store.saveAll(Tables.Cards, cards)
            _form.value = _form.value.copy(cardsMade = cards.size, notice = "Made ${cards.size} revision cards. They will show up in Revise.")
        }
    }

    private suspend fun subjectName(topicId: String?): String {
        var topic: Topic? = topicId?.let { store.get(Tables.Topics, it) }
        var guard = 0
        while (topic != null && topic.level > 1 && guard < 6) {
            val parent = topic.parentId ?: break
            topic = store.get(Tables.Topics, parent)
            guard++
        }
        return topic?.title ?: "Explain it back"
    }

    override fun onCleared() {
        recordJob?.cancel()
        clockJob?.cancel()
        tts.stop()
        super.onCleared()
    }
}

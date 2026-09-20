package com.naveen.civilscompanion.ui.revise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.speech.VoiceEvent
import com.naveen.civilscompanion.speech.VoiceInput
import com.naveen.civilscompanion.srs.CardMemory
import com.naveen.civilscompanion.srs.Fsrs
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

data class SessionUi(
    val loading: Boolean = true,
    val total: Int = 0,
    val index: Int = 0,
    val card: Card? = null,
    val topicTitle: String = "",
    val reason: String = "",
    val pastPapers: Boolean = false,
    val subjectMix: String = "",
    val revealed: Boolean = false,
    val intervals: Map<Int, String> = emptyMap(),
    val finished: Boolean = false,
    val counts: List<Int> = listOf(0, 0, 0, 0), // Again, Hard, Good, Easy
    val handsFree: Boolean = false,
    val status: String = "",
    val canUndo: Boolean = false,
)

private data class CardMeta(val title: String, val reason: String, val pastPapers: Boolean)

/** One revision session (spec 6.5): the queue is fixed when it opens, cards are graded with FSRS on the tablet. */
@HiltViewModel
class ReviseSessionViewModel @Inject constructor(
    private val repo: ReviseRepository,
    private val kv: KvRepository,
    private val tts: TtsSpeaker,
    private val voice: VoiceInput,
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    private var order: List<String> = emptyList()
    private var cards: Map<String, Card> = emptyMap()
    private var meta: Map<String, CardMeta> = emptyMap()
    private var retention: Double = Fsrs.DEFAULT_RETENTION
    private var last: GradeResult? = null
    private var lastGrade: Int = 0
    private var loopJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val q = repo.snapshot()
        retention = repo.settingsOnce().retention
        val ids = ArrayList<String>()
        val info = HashMap<String, CardMeta>()
        for (g in q.groups) {
            val importance = g.topicId?.let { q.topics[it]?.importance } ?: 0.0
            for (id in g.cardIds) {
                ids.add(id)
                info[id] = CardMeta(g.title, g.reason, importance >= 0.7)
            }
        }
        order = ids
        meta = info
        cards = q.cards
        val mix = q.groups.take(3).joinToString(" · ") { it.title }
        _ui.value = SessionUi(loading = false, total = ids.size, subjectMix = mix, handsFree = false, finished = ids.isEmpty())
        if (ids.isNotEmpty()) show(0)
    }

    private fun show(index: Int) {
        if (index >= order.size) {
            _ui.value = _ui.value.copy(finished = true, card = null, index = order.size, revealed = false, status = "")
            return
        }
        val card = cards[order[index]]
        if (card == null) {
            show(index + 1)
            return
        }
        val m = meta[card.id]
        val engine = Fsrs.forRetention(retention)
        val memory: CardMemory? = card.fsrsState?.let { CardMemory.fromJson(it) }
        val labels = engine.nextIntervals(memory, Instant.now()).mapValues { ReviseData.intervalLabel(it.value) }
        _ui.value = _ui.value.copy(
            index = index,
            card = card,
            topicTitle = m?.title.orEmpty(),
            reason = m?.reason.orEmpty(),
            pastPapers = m?.pastPapers ?: false,
            revealed = false,
            intervals = labels,
            finished = false,
        )
    }

    // ------------------------------------------------------------------ manual controls
    fun reveal() {
        stopHandsFree()
        revealNow()
    }

    private fun revealNow() {
        _ui.value = _ui.value.copy(revealed = true)
    }

    fun grade(g: Int) {
        stopHandsFree()
        viewModelScope.launch { applyGrade(g) }
    }

    private suspend fun applyGrade(g: Int) {
        val card = _ui.value.card ?: return
        val result = repo.grade(card, g, retention)
        last = result
        lastGrade = g
        val counts = _ui.value.counts.toMutableList()
        counts[g - 1] = counts[g - 1] + 1
        _ui.value = _ui.value.copy(counts = counts, canUndo = true)
        show(_ui.value.index + 1)
    }

    /** Takes back the last grade and shows that card again. */
    fun undo() {
        val result = last ?: return
        val g = lastGrade
        stopHandsFree()
        viewModelScope.launch {
            repo.undo(result, retention)
            last = null
            val counts = _ui.value.counts.toMutableList()
            if (g in 1..4) counts[g - 1] = Math.max(counts[g - 1] - 1, 0)
            cards = cards + (result.before.id to result.before)
            val index = order.indexOf(result.before.id).coerceAtLeast(0)
            _ui.value = _ui.value.copy(counts = counts, canUndo = false, finished = false)
            show(index)
        }
    }

    /** Reads the question, or the answer once it is shown. */
    fun listen() {
        val s = _ui.value
        val card = s.card ?: return
        val text = if (s.revealed) card.back else card.front
        tts.speak(ReviseData.speechText(text), "en-IN", speed())
    }

    fun stopSpeaking() {
        tts.stop()
    }

    private fun speed(): Float =
        (kv.get(KEY_SPEED) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.floatOrNull?.coerceIn(0.5f, 2.0f) ?: 1.0f

    // ------------------------------------------------------------------ hands-free
    /** The owner switched hands-free on or off (the screen asks for the microphone first). */
    fun setHandsFree(on: Boolean) {
        viewModelScope.launch { kv.put(KEY_HANDS_FREE, JsonPrimitive(on)) }
        if (on) {
            _ui.value = _ui.value.copy(handsFree = true, status = "Hands-free: listening after each card.")
            loopJob?.cancel()
            loopJob = viewModelScope.launch { runHandsFree() }
        } else {
            stopHandsFree()
        }
    }

    private fun stopHandsFree() {
        loopJob?.cancel()
        loopJob = null
        tts.stop()
        if (_ui.value.handsFree) _ui.value = _ui.value.copy(handsFree = false, status = "")
    }

    private suspend fun runHandsFree() {
        while (_ui.value.handsFree && !_ui.value.finished) {
            val keepGoing = handsFreeRound()
            if (!keepGoing) {
                _ui.value = _ui.value.copy(handsFree = false, status = "Hands-free stopped. You can carry on with the buttons.")
                return
            }
        }
    }

    /** One card by voice. Returns false when hands-free should stop. */
    private suspend fun handsFreeRound(): Boolean {
        val card = _ui.value.card ?: return false
        speakAndWait(ReviseData.speechText(card.front))
        val answer = listenOnce()
        if (answer != null && HandsFree.wantsStop(answer)) return false
        revealNow()
        speakAndWait(ReviseData.speechText(card.back))
        var tries = 0
        while (tries < 3) {
            if (tries > 0) speakAndWait("Say again, hard, good or easy.")
            val said = listenOnce()
            if (said == null) {
                tries++
                continue
            }
            if (HandsFree.wantsStop(said)) return false
            val g = HandsFree.parseGrade(said)
            if (g != null) {
                applyGrade(g)
                return true
            }
            tries++
        }
        return false
    }

    private suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        val rate = speed()
        withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.speak(text, "en-IN", rate) { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation { tts.stop() }
            }
        }
    }

    private suspend fun listenOnce(): String? {
        return try {
            val event = voice.listen().first { it is VoiceEvent.Final || it is VoiceEvent.Failed }
            (event as? VoiceEvent.Final)?.text?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSuchElementException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override fun onCleared() {
        loopJob?.cancel()
        tts.stop()
        super.onCleared()
    }

    private companion object {
        const val KEY_HANDS_FREE = "voice.hands_free_revision"
        const val KEY_SPEED = "voice.speed"
    }
}

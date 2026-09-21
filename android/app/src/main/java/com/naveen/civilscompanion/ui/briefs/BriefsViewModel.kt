package com.naveen.civilscompanion.ui.briefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.NavEvents
import com.naveen.civilscompanion.PendingAction
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.decodeFacts
import com.naveen.civilscompanion.data.decodeStrings
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.local.BriefEntity
import com.naveen.civilscompanion.data.local.NewsItemEntity
import com.naveen.civilscompanion.data.remote.dto.FactDto
import com.naveen.civilscompanion.data.repo.AudioStore
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.playback.PlayerConnection
import com.naveen.civilscompanion.playback.PlayerUiState
import com.naveen.civilscompanion.playback.QueueItem
import com.naveen.civilscompanion.speech.TtsSpeaker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import retrofit2.HttpException

data class BriefRow(val id: String, val title: String, val dayText: String, val status: String)

data class ItemUi(
    val id: String,
    val number: Int,
    val title: String,
    val label: String,
    val duration: String,
    val heard: Boolean,
    val playing: Boolean,
    val hasAudio: Boolean,
)

data class DetailUi(
    val id: String,
    val title: String,
    val source: String,
    val summary: String,
    val papers: List<String>,
    val isAp: Boolean,
    val facts: List<FactDto>,
    val mainsAngle: String,
    val cardCount: Int,
    val url: String,
)

data class BriefsUiState(
    val briefs: List<BriefRow> = emptyList(),
    val selected: BriefRow? = null,
    val headline: String = "",
    val note: String = "",
    val items: List<ItemUi> = emptyList(),
    val detail: DetailUi? = null,
    val player: PlayerUiState = PlayerUiState(),
    val playingThisBrief: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

private data class Sources(
    val brief: BriefEntity?,
    val items: List<NewsItemEntity>,
    val heard: Set<String>,
    val cardCounts: Map<String, Int>,
    val downloaded: Set<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BriefsViewModel @Inject constructor(
    private val db: AppDatabase,
    private val sync: SyncRepository,
    private val player: PlayerConnection,
    audio: AudioStore,
    private val navEvents: NavEvents,
    private val json: Json,
    private val tts: TtsSpeaker,
) : ViewModel() {

    private val selectedBriefId = MutableStateFlow<String?>(null)
    private val pickedItemId = MutableStateFlow<String?>(null)
    private val flash = MutableStateFlow(Flash())
    private var pollJob: Job? = null

    private data class Flash(val busy: Boolean = false, val message: String? = null)

    private val briefs = db.briefs().observeAll()

    private val chosenBrief = combine(briefs, selectedBriefId) { list, id ->
        list.firstOrNull { it.id == id }
            ?: list.firstOrNull { it.status == "ready" }
            ?: list.firstOrNull()
    }

    private val sources = combine(
        chosenBrief,
        chosenBrief.flatMapLatest { b ->
            if (b == null) flowOf(emptyList<NewsItemEntity>()) else db.newsItems().observe(decodeStrings(json, b.itemIdsJson))
        },
        db.progress().observeHeard(),
        db.records().observeK2Counts("cards"),
        audio.downloaded,
    ) { brief, items, heard, counts, downloaded ->
        val order = brief?.let { decodeStrings(json, it.itemIdsJson) }.orEmpty()
        val sorted = items.sortedBy { order.indexOf(it.id) }
        Sources(brief, sorted, heard.toSet(), counts.associate { it.sourceId to it.n }, downloaded)
    }

    val state: StateFlow<BriefsUiState> = combine(
        briefs, sources, pickedItemId, player.state, flash,
    ) { all, src, picked, p, t -> buildState(all, src, picked, p, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BriefsUiState(player = player.state.value))

    init {
        player.ensureConnected()
        // The detail pane follows whatever is being read aloud.
        viewModelScope.launch {
            player.state.map { it.currentItemId }.distinctUntilChanged().collect { id ->
                if (id != null) pickedItemId.value = id
            }
        }
        viewModelScope.launch { navEvents.pending.collect { it?.let { action -> handle(action) } } }
        refresh(silent = true)
    }

    // ---------------------------------------------------------------- actions

    fun selectBrief(id: String) {
        selectedBriefId.value = id
        pickedItemId.value = null
    }

    /** Tap on an item: show it and read it aloud from there. */
    fun selectItem(itemId: String) {
        pickedItemId.value = itemId
        val s = state.value
        val item = s.items.firstOrNull { it.id == itemId } ?: return
        if (!item.hasAudio) {
            readWithTablet(itemId)
            return
        }
        if (s.playingThisBrief && player.jumpTo(itemId)) return
        playFrom(itemId)
    }

    /** Big play button: pause/resume, or start this brief from the first unheard item. */
    fun togglePlay() {
        val s = state.value
        if (s.playingThisBrief) {
            player.toggle()
            return
        }
        val start = s.items.firstOrNull { it.hasAudio && !it.heard } ?: s.items.firstOrNull { it.hasAudio }
        if (start == null) {
            // No audio file from the server: use the tablet's own voice instead.
            if (tts.speaking.value) {
                tts.stop()
                return
            }
            readWithTablet(pickedItemId.value)
            return
        }
        playFrom(start.id)
    }

    /** Fallback when a brief has no audio file: the tablet's own speech engine reads the items one after another. */
    private fun readWithTablet(startId: String?) {
        val brief = state.value.selected ?: return
        viewModelScope.launch {
            val entity = db.briefs().get(brief.id) ?: return@launch
            val items = orderedItems(entity)
            if (items.isEmpty()) {
                say("This brief has no items yet.")
                return@launch
            }
            val first = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            val texts = items.map { itemScript(it) }
            say("No audio file for this brief, so the tablet's voice is reading it. Tap play to stop.")
            tts.speakSentences(
                texts,
                startIndex = first,
                onSentence = { index -> items.getOrNull(index)?.let { pickedItemId.value = it.id } },
            )
        }
    }

    private fun itemScript(item: NewsItemEntity): String {
        val parts = ArrayList<String>()
        parts.add(item.title.trimEnd('.') + ".")
        parts.add(item.summary.trim())
        val facts = decodeFacts(json, item.prelimsFactsJson)
        if (facts.isNotEmpty()) {
            parts.add("Must remember.")
            for (f in facts) parts.add(f.q.trimEnd('?', '.') + "? " + f.a.trimEnd('.') + ".")
        }
        if (item.mainsAngle.isNotBlank()) parts.add("Mains angle. " + item.mainsAngle.trim())
        return parts.joinToString(" ")
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }

    fun back15() = player.seekBy(-15_000)
    fun forward15() = player.seekBy(15_000)
    fun next() = player.next()
    fun previous() = player.previous()
    fun seek(fraction: Float) = player.seekToFraction(fraction)
    fun setSpeed(speed: Float) = player.setSpeed(speed)
    fun setSleep(minutes: Int?) = player.setSleepTimer(minutes)
    fun dismissMessage() = flash.value.let { flash.value = it.copy(message = null) }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            try {
                sync.pull()
            } catch (e: IOException) {
                if (!silent) say("Cannot reach the server. Showing saved briefs.")
            } catch (e: HttpException) {
                if (!silent) say("The server answered with an error (${e.code()}).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) say("Something went wrong while syncing.")
            }
        }
    }

    /** "Prepare a brief now": the server reads the news and records the audio (a few minutes). */
    fun prepareNow() {
        if (flash.value.busy) return
        flash.value = Flash(busy = true)
        viewModelScope.launch {
            try {
                val id = sync.prepareBriefNow()
                selectedBriefId.value = id
                say("Preparing your brief. This takes a few minutes.", busy = true)
                sync.pull()
                waitUntilDone(id)
            } catch (e: HttpException) {
                say(if (e.code() == 409) "A brief is already being prepared." else "The server refused (${e.code()}).")
            } catch (e: IOException) {
                say("Cannot reach the server.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Something went wrong. Please try again.")
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun handle(action: PendingAction) {
        if (action.action != AppLinks.ACTION_PLAY_BRIEF && action.action != AppLinks.ACTION_OPEN_BRIEF) return
        navEvents.consume()
        runCatching { sync.pull() }
        val briefId = action.briefId ?: return
        selectedBriefId.value = briefId
        pickedItemId.value = null
        if (action.action == AppLinks.ACTION_PLAY_BRIEF) {
            val brief = db.briefs().get(briefId) ?: return
            val items = orderedItems(brief)
            val first = items.firstOrNull { it.audioUrl != null } ?: return
            play(brief, items, first.id)
        }
    }

    private fun playFrom(itemId: String) {
        val brief = state.value.selected ?: return
        viewModelScope.launch {
            val entity = db.briefs().get(brief.id) ?: return@launch
            play(entity, orderedItems(entity), itemId)
        }
    }

    private fun play(brief: BriefEntity, items: List<NewsItemEntity>, startId: String) {
        val queue = items.map { QueueItem(it.id, it.title, it.audioUrl) }
        val ok = player.playQueue(BriefTimes.label(brief.kind), queue, startId)
        if (!ok) readWithTablet(startId)
    }

    private suspend fun orderedItems(brief: BriefEntity): List<NewsItemEntity> {
        val order = decodeStrings(json, brief.itemIdsJson)
        return db.newsItems().get(order).sortedBy { order.indexOf(it.id) }
    }

    /** Checks every 20 seconds (up to 10 minutes) until the brief is ready or failed. */
    private fun waitUntilDone(briefId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(30) {
                delay(20_000)
                runCatching { sync.pull() }
                val status = db.briefs().get(briefId)?.status
                if (status == "ready" || status == "failed") {
                    say(if (status == "ready") "Your brief is ready." else "The brief could not be prepared.")
                    return@launch
                }
            }
            say("Still working. You will get a notification when it is ready.")
        }
    }

    private fun say(text: String, busy: Boolean = false) {
        flash.value = Flash(busy = busy, message = text)
    }

    private fun buildState(
        all: List<BriefEntity>,
        src: Sources,
        picked: String?,
        p: PlayerUiState,
        t: Flash,
    ): BriefsUiState {
        val rows = all.map { it.toRow() }
        val brief = src.brief
        val itemsUi = src.items.mapIndexed { i, item ->
            ItemUi(
                id = item.id,
                number = i + 1,
                title = item.title,
                label = subjectOf(item),
                duration = BriefFormat.minutes(item.audioSeconds),
                heard = item.id in src.heard,
                playing = p.isPlaying && p.currentItemId == item.id,
                hasAudio = item.audioUrl != null,
            )
        }
        val shown = src.items.firstOrNull { it.id == picked } ?: src.items.firstOrNull()
        val detail = shown?.let { item ->
            DetailUi(
                id = item.id,
                title = item.title,
                source = item.source,
                summary = item.summary,
                papers = decodeStrings(json, item.papersJson),
                isAp = item.isApSpecific,
                facts = decodeFacts(json, item.prelimsFactsJson),
                mainsAngle = item.mainsAngle,
                cardCount = src.cardCounts[item.id] ?: 0,
                url = item.url,
            )
        }
        val withAudio = src.items.filter { it.audioUrl != null }
        val saved = withAudio.count { it.id in src.downloaded }
        val savedText = when {
            src.items.isEmpty() -> ""
            withAudio.isEmpty() -> "text only"
            saved == withAudio.size -> "saved for offline"
            else -> "$saved of ${withAudio.size} saved for offline"
        }
        val headline = brief?.let {
            listOfNotNull(
                BriefFormat.dayText(it.scheduledFor),
                if (src.items.isNotEmpty()) "${src.items.size} items" else null,
                savedText.ifBlank { null },
            ).joinToString(" · ")
        }.orEmpty()
        val note = when (brief?.status) {
            "preparing" -> "Preparing this brief…"
            "failed" -> brief?.note.orEmpty().ifBlank { "This brief could not be prepared." }
            else -> brief?.note.orEmpty()
        }
        val playingThis = p.hasQueue && src.items.isNotEmpty() && p.itemIds.all { id -> src.items.any { it.id == id } }
        return BriefsUiState(
            briefs = rows,
            selected = brief?.toRow(),
            headline = headline,
            note = note,
            items = itemsUi,
            detail = detail,
            player = p,
            playingThisBrief = playingThis,
            busy = t.busy,
            message = t.message,
        )
    }

    private fun subjectOf(item: NewsItemEntity): String {
        val paper = decodeStrings(json, item.papersJson).firstOrNull()
        return when {
            paper != null -> BriefFormat.shortLabel(paper)
            item.isApSpecific -> "AP"
            else -> item.source
        }
    }

    private fun BriefEntity.toRow() = BriefRow(
        id = id,
        title = BriefTimes.label(kind),
        dayText = "${BriefFormat.dayText(scheduledFor)}, ${BriefFormat.timeText(scheduledFor)}",
        status = status,
    )
}

package com.naveen.civilscompanion.ui.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Video
import com.naveen.civilscompanion.data.model.VideoNote
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.PlanBlocks
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/** The "what to listen for" text and where its request stands. */
data class SummaryUi(val text: String = "", val note: String = "", val asking: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val kv: KvRepository,
    private val api: VideosApi,
) : ViewModel() {
    private val videoId = MutableStateFlow("")
    private val summaryJob = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun open(id: String) {
        if (videoId.value != id) {
            videoId.value = id
            summaryJob.value = null
        }
    }

    val video: StateFlow<Video?> = videoId
        .flatMapLatest { id -> if (id.isBlank()) flowOf<Video?>(null) else store.observeOne(Tables.Videos, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notes: StateFlow<List<VideoNote>> = videoId
        .flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyList<VideoNote>())
            else store.observe(Tables.VideoNotes, RecordQuery(k1 = id, order = Order.NumberAsc))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val savedSummary: StateFlow<Pair<String, String>?> = videoId
        .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else kv.observe(VideoLogic.summaryKey(id)).map { VideoLogic.summaryOf(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val jobState: StateFlow<Pair<Boolean, String?>> = summaryJob
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<Pair<Boolean, String?>>(false to null)
            } else {
                jobs.observe(id).map { job ->
                    when (job?.status) {
                        "failed" -> false to job?.error.orEmpty().ifBlank { "The summary could not be made." }
                        "done", null -> false to null as String?
                        else -> true to null
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to null)

    val summary: StateFlow<SummaryUi> = kotlinx.coroutines.flow.combine(savedSummary, jobState) { saved, job ->
        SummaryUi(text = saved?.first.orEmpty(), note = saved?.second.orEmpty(), asking = job.first, error = job.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUi())

    fun addNote(seconds: Int, text: String) {
        val clean = text.trim()
        val id = videoId.value
        if (clean.isEmpty() || id.isBlank()) return
        viewModelScope.launch {
            store.save(Tables.VideoNotes, VideoNote(id = TimeUtil.newId(), videoId = id, seconds = seconds.coerceAtLeast(0), text = clean))
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { store.delete(Tables.VideoNotes, noteId) }
    }

    /** Called when the player reports that the owner of the video does not allow playing it here. */
    fun markNotEmbeddable() {
        val id = videoId.value
        if (id.isBlank()) return
        viewModelScope.launch { store.update(Tables.Videos, id) { it.copy(embeddable = false) } }
    }

    /** The video ended (or the owner ticked it): mark it watched and tick a "video" block of today's plan. */
    fun markWatched(watched: Boolean = true) {
        val id = videoId.value
        if (id.isBlank()) return
        viewModelScope.launch {
            val updated = store.update(Tables.Videos, id) { it.copy(watched = watched) } ?: return@launch
            if (watched) tickPlan(updated)
        }
    }

    private suspend fun tickPlan(video: Video) {
        val plan = store.list(Tables.DailyPlans, RecordQuery(k1 = TimeUtil.today())).firstOrNull() ?: return
        val block = PlanBlocks.parseAll(plan.blocks).firstOrNull { b ->
            b.kind == "video" && (b.ref == Routes.video(video.id) || (video.topicId != null && b.topicId == video.topicId))
        } ?: return
        store.update(Tables.DailyPlans, plan.id) { p ->
            JsonObject(p.completion + (block.id to JsonPrimitive(PlanBlocks.DONE))).let { p.copy(completion = it) }
        }
    }

    /** Asks the server for a short "what to listen for" (job video_summary). Works offline: it waits for the network. */
    fun askSummary() {
        val id = videoId.value
        if (id.isBlank()) return
        viewModelScope.launch { summaryJob.value = jobs.enqueue("video_summary", jobPayload("video_id" to id)) }
    }

    /** Looks the video up again (title, channel, whether it can play here). Needs internet. */
    fun refreshDetails() {
        val current = video.value ?: return
        viewModelScope.launch {
            message.value = try {
                val result = api.resolve(ResolveBody(current.youtubeId, current.topicId))
                result.video?.let { store.save(Tables.Videos, it) }
                "Details updated."
            } catch (e: HttpException) {
                VideoLogic.detailOf(runCatching { e.response()?.errorBody()?.string() }.getOrNull()) ?: "The server could not check this video (${e.code()})."
            } catch (e: IOException) {
                "Needs internet. Try again when you are online."
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

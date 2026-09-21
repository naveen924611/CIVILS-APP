package com.naveen.civilscompanion.ui.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Video
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class VideosExtra(
    val busy: Boolean = false,
    val message: String? = null,
    val results: List<SearchHit> = emptyList(),
    val filter: String = "all",
)

data class VideosUi(
    val videos: List<Video> = emptyList(),
    val extra: VideosExtra = VideosExtra(),
)

/** The list of saved videos (all, or those of one topic), adding a video by link, and searching. Shared by the Videos screen and topic panels. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VideosViewModel @Inject constructor(
    private val store: RecordStore,
    private val api: VideosApi,
) : ViewModel() {
    private val topic = MutableStateFlow<String?>(null)
    private val extra = MutableStateFlow(VideosExtra())

    val ui: StateFlow<VideosUi> = combine(
        topic.flatMapLatest { t -> store.observe(Tables.Videos, RecordQuery(k1 = t)) },
        extra,
    ) { videos, x -> VideosUi(VideoLogic.sorted(VideoLogic.filtered(videos, x.filter)), x) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideosUi())

    val topicId: String? get() = topic.value

    fun setTopic(id: String?) {
        topic.value = id
    }

    fun setFilter(filter: String) = extra.update { it.copy(filter = filter) }

    fun dismissMessage() = extra.update { it.copy(message = null) }

    /** Paste a link: checks it on the server (title, channel, whether it can play inside the app) and saves it. */
    fun addLink(text: String) {
        val id = VideoLogic.parseYoutubeId(text)
        if (id == null) {
            extra.update { it.copy(message = "That does not look like a YouTube link. Paste the whole link from the address bar or the Share button.") }
            return
        }
        viewModelScope.launch {
            extra.update { it.copy(busy = true, message = null) }
            val message = try {
                val result = api.resolve(ResolveBody(id, topic.value))
                val video = result.video
                if (video != null) store.save(Tables.Videos, video)
                when {
                    video == null -> "The server did not send the video back. Please try again."
                    !video.embeddable -> "Saved. This video opens in YouTube because its owner does not allow playing it here."
                    result.created -> "Saved. It plays inside the app."
                    else -> "This video was already saved."
                }
            } catch (e: HttpException) {
                VideoLogic.detailOf(runCatching { e.response()?.errorBody()?.string() }.getOrNull())
                    ?: "The server could not check this link (${e.code()})."
            } catch (e: IOException) {
                "Adding a link needs internet, to check the video. Please try again when you are online."
            }
            extra.update { it.copy(busy = false, message = message) }
        }
    }

    /** Search YouTube through the server (needs a YouTube key set on the server; otherwise a plain message comes back). */
    fun search(query: String) {
        viewModelScope.launch {
            extra.update { it.copy(busy = true, message = null, results = emptyList()) }
            val next = try {
                val r = api.search(query.trim(), topic.value)
                val note = r.message.ifBlank { if (r.results.isEmpty()) "No videos found." else "" }
                extra.value.copy(busy = false, results = r.results, message = note.ifBlank { null })
            } catch (e: HttpException) {
                val text = if (e.code() == 429) "Too many searches for now. Please wait a little." else "Search did not work (${e.code()}). You can paste a link instead."
                extra.value.copy(busy = false, message = text)
            } catch (e: IOException) {
                extra.value.copy(busy = false, message = "Searching needs internet. You can still open saved videos and your notes.")
            }
            extra.value = next
        }
    }

    fun clearResults() = extra.update { it.copy(results = emptyList()) }
}

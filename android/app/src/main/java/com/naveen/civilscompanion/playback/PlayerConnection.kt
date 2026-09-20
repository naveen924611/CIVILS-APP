package com.naveen.civilscompanion.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.local.ItemProgressEntity
import com.naveen.civilscompanion.data.repo.AudioStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** One thing to play: a brief item and where its audio is. */
data class QueueItem(val id: String, val title: String, val audioPath: String?)

data class PlayerUiState(
    val hasQueue: Boolean = false,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val label: String = "",
    val itemIds: List<String> = emptyList(),
    val index: Int = 0,
    val currentItemId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepMinutes: Int? = null,
    val sleepRemainingMs: Long = 0,
)

/** The screen's remote control for the background player (PlaybackService). */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Prefs,
    private val audio: AudioStore,
    private val tokens: TokenStore,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var sleepEndsAt = 0L
    private var sleepMinutes: Int? = null
    private var lastItemId: String? = null
    private var label = ""

    private val _state = MutableStateFlow(PlayerUiState(speed = prefs.playbackSpeed))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastItemId?.let { markHeard(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) controller?.currentMediaItem?.mediaId?.let { markHeard(it) }
        }

        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Connects to the background player once (safe to call many times). */
    fun ensureConnected() {
        if (controller != null || connecting?.isActive == true) return
        connecting = scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val c = runCatching { future.await(context) }.getOrNull() ?: return@launch
            c.addListener(listener)
            controller = c
            c.setPlaybackSpeed(prefs.playbackSpeed)
            publish(c)
            startTicker()
        }
    }

    /** Plays [items] in order from [startId]. Returns false when none of them has audio. */
    fun playQueue(queueLabel: String, items: List<QueueItem>, startId: String?): Boolean {
        val playable = items.mapNotNull { item ->
            val uri = uriFor(item) ?: return@mapNotNull null
            item to uri
        }
        if (playable.isEmpty()) return false
        label = queueLabel
        scope.launch {
            ensureConnected()
            connecting?.join()
            val c = controller ?: return@launch
            val total = playable.size
            val mediaItems = playable.mapIndexed { i, (item, uri) ->
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist("$queueLabel · item ${i + 1} of $total")
                            .build(),
                    )
                    .build()
            }
            val start = playable.indexOfFirst { it.first.id == startId }.coerceAtLeast(0)
            c.setMediaItems(mediaItems, start, 0L)
            c.setPlaybackSpeed(prefs.playbackSpeed)
            c.prepare()
            c.play()
        }
        return true
    }

    /** True when the item is already in the loaded queue (then tapping it just jumps there). */
    fun jumpTo(itemId: String): Boolean {
        val c = controller ?: return false
        val index = _state.value.itemIds.indexOf(itemId)
        if (index < 0) return false
        c.seekTo(index, 0L)
        c.play()
        return true
    }

    fun toggle() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) {
            c.seekTo(0, 0L)
            c.play()
        } else if (c.playWhenReady) {
            c.pause()
        } else {
            c.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0L)
        c.seekTo(target)
    }

    fun seekToFraction(fraction: Float) {
        val c = controller ?: return
        val d = c.duration
        if (d > 0) c.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun setSpeed(speed: Float) {
        prefs.playbackSpeed = speed
        controller?.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    /** Pauses after [minutes]. Null turns the timer off. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes == null) {
            sleepEndsAt = 0L
            controller?.let { publish(it) }
            return
        }
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            sleepMinutes = null
            sleepEndsAt = 0L
            controller?.let { publish(it) }
        }
        controller?.let { publish(it) }
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        setSleepTimer(null)
    }

    // ------------------------------------------------------------------ internals

    private fun uriFor(item: QueueItem): Uri? {
        if (audio.has(item.id)) return Uri.fromFile(audio.file(item.id))
        val path = item.audioPath ?: return null
        return Uri.parse(tokens.serverUrl + path)
    }

    private fun markHeard(itemId: String) {
        scope.launch(Dispatchers.IO) { db.progress().upsert(ItemProgressEntity(itemId, true)) }
    }

    private fun publish(p: Player) {
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        val current = p.currentMediaItem?.mediaId
        val remaining = if (sleepEndsAt > 0) (sleepEndsAt - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
        lastItemId = current
        _state.value = PlayerUiState(
            hasQueue = ids.isNotEmpty(),
            isPlaying = p.playWhenReady &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING),
            loading = p.playbackState == Player.STATE_BUFFERING,
            label = label,
            itemIds = ids,
            index = p.currentMediaItemIndex.coerceAtLeast(0),
            currentItemId = current,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.takeIf { it > 0 } ?: 0L,
            speed = p.playbackParameters.speed,
            sleepMinutes = sleepMinutes,
            sleepRemainingMs = remaining,
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                controller?.let { publish(it) }
            }
        }
    }
}

private suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        cont.invokeOnCancellation { this@await.cancel(false) }
    }

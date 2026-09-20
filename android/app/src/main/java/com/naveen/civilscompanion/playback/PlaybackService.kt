package com.naveen.civilscompanion.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.naveen.civilscompanion.CivilsApp
import com.naveen.civilscompanion.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Plays brief audio in the background: keeps going with the screen off, shows lock-screen and
 * notification controls, and answers headphone / Bluetooth buttons (all through the media session).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var http: PlaybackDataSource
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http.factory))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true, // pause when another app needs the speaker (a call, for example)
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // keep playing with the screen off
            .build()
        session = MediaSession.Builder(this, player).setId("civils-brief").build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CivilsApp.PLAYER_CHANNEL)
                .setChannelName(R.string.channel_player)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

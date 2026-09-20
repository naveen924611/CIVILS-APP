package com.naveen.civilscompanion.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Streams audio from the server with the login token (used only when a file is not saved on the tablet yet). */
@Singleton
@OptIn(UnstableApi::class)
class PlaybackDataSource @Inject constructor(@Named("authed") client: OkHttpClient) {
    val factory: OkHttpDataSource.Factory = OkHttpDataSource.Factory(client)
}

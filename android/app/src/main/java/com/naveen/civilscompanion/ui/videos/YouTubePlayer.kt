package com.naveen.civilscompanion.ui.videos

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** What the screen can ask of the player, and what the player last told us. */
class PlayerController {
    /** The current second of the video (updated twice a second while it is ready). */
    var seconds by mutableIntStateOf(0)
        internal set
    var ready by mutableStateOf(false)
        internal set
    var playing by mutableStateOf(false)
        internal set
    var errorCode by mutableIntStateOf(0)
        internal set
    internal var web: WebView? = null

    fun seekTo(second: Int) {
        web?.evaluateJavascript("seekTo(${second.coerceAtLeast(0)})", null)
    }
}

// @Keep: the web page calls these methods by name, so the release build must not rename or remove them.
@Keep
private class PlayerBridge(
    private val controller: PlayerController,
    private val onBlocked: () -> Unit,
    private val onEnded: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Keep
    @JavascriptInterface
    fun onReady() {
        main.post { controller.ready = true }
    }

    @Keep
    @JavascriptInterface
    fun onTime(seconds: Int) {
        main.post { controller.seconds = seconds }
    }

    @Keep
    @JavascriptInterface
    fun onState(code: Int) {
        main.post {
            controller.playing = code == 1
            if (code == 0) onEnded()
        }
    }

    @Keep
    @JavascriptInterface
    fun onError(code: Int) {
        main.post {
            if (VideoLogic.embeddingBlocked(code)) onBlocked() else controller.errorCode = code
        }
    }
}

/**
 * YouTube's official IFrame player inside a WebView. Nothing is downloaded or saved: it only plays the stream.
 * [onBlocked] is called when the owner of the video does not allow playing it inside other apps.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(
    youtubeId: String,
    controller: PlayerController,
    onBlocked: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked by rememberUpdatedState(onBlocked)
    val ended by rememberUpdatedState(onEnded)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(PlayerBridge(controller, { blocked() }, { ended() }), "Android")
                controller.web = this
                loadDataWithBaseURL("https://www.youtube.com", VideoLogic.embedHtml(youtubeId), "text/html", "utf-8", null)
            }
        },
    )
    DisposableEffect(youtubeId) {
        onDispose {
            controller.web?.let { view ->
                view.stopLoading()
                view.destroy()
            }
            controller.web = null
            controller.ready = false
        }
    }
}

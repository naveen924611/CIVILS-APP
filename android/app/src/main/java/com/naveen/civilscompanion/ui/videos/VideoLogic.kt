package com.naveen.civilscompanion.ui.videos

import com.naveen.civilscompanion.data.model.Video
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Plain Kotlin for the Videos screens: reading links, time text, the player page, sorting. No video file is ever stored. */
object VideoLogic {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be", "www.youtu.be",
    )
    private val PATH_KINDS = setOf("embed", "shorts", "live", "v", "e")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** The 11-letter id from a pasted link (watch, short, embed, shorts, live) or the bare id. Null when it is not YouTube. */
    fun parseYoutubeId(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (ID.matches(raw)) return raw
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = try {
            URI(withScheme)
        } catch (e: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val candidate: String? = when {
            host.endsWith("youtu.be") -> parts.firstOrNull()
            parts.firstOrNull() == "watch" ->
                (uri.rawQuery ?: "").split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            parts.size >= 2 && parts[0] in PATH_KINDS -> parts[1]
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    fun watchUrl(id: String, seconds: Int = 0): String =
        "https://www.youtube.com/watch?v=$id" + if (seconds > 0) "&t=${seconds}s" else ""

    /** 75 becomes "1:15"; 3725 becomes "1:02:05". */
    fun formatTime(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "1:15", "01:15", "1:02:05" or a plain number of seconds. Null when it is not a time. */
    fun parseTime(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return when (numbers.size) {
            1 -> numbers[0]
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
    }

    /** "12 min", "1 h 5 min", or "" when the length is not known. */
    fun durationLabel(seconds: Int): String {
        if (seconds <= 0) return ""
        val minutes = (seconds + 30) / 60
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "${maxOf(minutes, 1)} min"
    }

    /** YouTube's player error codes that mean "this video may not be played inside other apps". */
    fun embeddingBlocked(code: Int): Boolean = code == 101 || code == 150 || code == 152 || code == 153

    /** Message for the other error codes. */
    fun errorText(code: Int): String = when (code) {
        100 -> "This video was removed or is private."
        2 -> "The link does not look right."
        5 -> "The player could not play this video. Try Open in YouTube."
        else -> "The video could not be played (code $code). Try Open in YouTube."
    }

    /**
     * The small web page that holds YouTube's official IFrame player. It reports the current second and errors to the
     * app through the `Android` bridge. Returns "" when the id is not a real video id, so nothing odd is ever put in a page.
     */
    fun embedHtml(id: String): String {
        if (!isValidId(id)) return ""
        return """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            var player;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
                playerVars:{playsinline:1,rel:0,modestbranding:1,origin:'https://www.youtube.com'},
                events:{
                  onReady:function(){Android.onReady();setInterval(function(){try{Android.onTime(Math.floor(player.getCurrentTime()))}catch(e){}},500)},
                  onStateChange:function(e){Android.onState(e.data)},
                  onError:function(e){Android.onError(e.data)}}});}
            function seekTo(s){if(player){player.seekTo(s,true);player.playVideo();}}
            </script></body></html>
        """.trimIndent()
    }

    /** Unwatched videos first, then by title. */
    fun sorted(videos: List<Video>): List<Video> =
        videos.sortedWith(compareBy<Video>({ it.watched }, { it.title.lowercase() }))

    /** filter: "all", "todo" (not watched yet) or "watched". */
    fun filtered(videos: List<Video>, filter: String): List<Video> = when (filter) {
        "todo" -> videos.filter { !it.watched }
        "watched" -> videos.filter { it.watched }
        else -> videos
    }

    /** The text of an error answer from the server: {"detail": "..."}. Null when there is none. */
    fun detailOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val element: JsonElement = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        return ((element as? JsonObject)?.get("detail") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /** The saved "what to listen for" text: setting video.summary.<id> = {"text": "...", "note": "..."}. */
    fun summaryOf(e: JsonElement?): Pair<String, String>? {
        val o = e as? JsonObject ?: return null
        val text = (o["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text to (o["note"] as? JsonPrimitive)?.content.orEmpty()
    }

    fun summaryKey(videoId: String): String = "video.summary.${videoId.lowercase()}"
}

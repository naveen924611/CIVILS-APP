package com.naveen.civilscompanion.ui.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.naveen.civilscompanion.data.repo.KvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `voice.*` settings (spec 6.8), shared with the server through KvRepository. Other screens may use this too. */
object VoiceKeys {
    /** Reading voice as a language tag TtsSpeaker understands: en-IN (default), en-GB, en-US or te-IN. */
    const val NAME = "voice.name"
    const val SPEED = "voice.speed"
    const val SPEAK_ON_HEADPHONES = "voice.speak_on_headphones"
    const val HANDS_FREE_REVISION = "voice.hands_free_revision"
    const val WAKE_PHRASE = "voice.wake_phrase"
    const val HEADPHONE_MAP = "voice.headphone_map"

    /** What the microphone listens for: en-IN (default) or te-IN. */
    const val LISTEN_LANGUAGE = "voice.listen_language"

    const val DEFAULT_NAME = "en-IN"
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_LISTEN = "en-IN"

    /** Headphone button gestures the owner can map (the media player applies them). */
    val HEADPHONE_GESTURES: List<Pair<String, String>> = listOf(
        "single" to "Single press",
        "double" to "Double press",
        "triple" to "Triple press",
    )

    val HEADPHONE_ACTIONS: List<Pair<String, String>> = listOf(
        "play_pause" to "Play or pause",
        "next" to "Next item",
        "back15" to "Back 15 seconds",
        "previous" to "Previous item",
    )

    val DEFAULT_HEADPHONE_MAP: Map<String, String> = mapOf(
        "single" to "play_pause", "double" to "next", "triple" to "back15",
    )
}

/** Reads the saved `voice.headphone_map` (a JSON object gesture -> action); missing gestures get the default. */
fun headphoneMapOf(saved: JsonElement?): Map<String, String> {
    val result = VoiceKeys.DEFAULT_HEADPHONE_MAP.toMutableMap()
    (saved as? JsonObject)?.forEach { (gesture, value) ->
        val action = (value as? JsonPrimitive)?.content
        if (action != null) result[gesture] = action
    }
    return result
}

@Singleton
class VoiceSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kv: KvRepository,
) {
    fun speed(): Float = kv.get(VoiceKeys.SPEED, Float.serializer(), VoiceKeys.DEFAULT_SPEED).coerceIn(0.5f, 2.0f)

    fun voiceName(): String = kv.get(VoiceKeys.NAME, String.serializer(), VoiceKeys.DEFAULT_NAME)

    fun listenLanguage(): String = kv.get(VoiceKeys.LISTEN_LANGUAGE, String.serializer(), VoiceKeys.DEFAULT_LISTEN)

    fun speakOnHeadphones(): Boolean = kv.get(VoiceKeys.SPEAK_ON_HEADPHONES, Boolean.serializer(), false)

    suspend fun setSpeed(value: Float) {
        kv.put(VoiceKeys.SPEED, JsonPrimitive(value.coerceIn(0.5f, 2.0f)))
    }

    /** gesture -> action, filled with the defaults for gestures that were never changed. */
    fun headphoneMap(): Map<String, String> = headphoneMapOf(kv.get(VoiceKeys.HEADPHONE_MAP))

    /** True when wired or Bluetooth headphones are connected right now. */
    fun headphonesConnected(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                -> true
                else -> false
            }
        }
    }
}

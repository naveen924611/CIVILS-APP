package com.naveen.civilscompanion.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.ui.voice.VoiceKeys
import com.naveen.civilscompanion.ui.voice.headphoneMapOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@HiltViewModel
class AskSettingsViewModel @Inject constructor(
    private val kv: KvRepository,
    private val tts: TtsSpeaker,
) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val name: StateFlow<String> = kv.observe(VoiceKeys.NAME, String.serializer(), VoiceKeys.DEFAULT_NAME)
        .stateIn(viewModelScope, started, VoiceKeys.DEFAULT_NAME)
    val listen: StateFlow<String> = kv.observe(VoiceKeys.LISTEN_LANGUAGE, String.serializer(), VoiceKeys.DEFAULT_LISTEN)
        .stateIn(viewModelScope, started, VoiceKeys.DEFAULT_LISTEN)
    val speed: StateFlow<Float> = kv.observe(VoiceKeys.SPEED, Float.serializer(), VoiceKeys.DEFAULT_SPEED)
        .stateIn(viewModelScope, started, VoiceKeys.DEFAULT_SPEED)
    val speakOnHeadphones: StateFlow<Boolean> = kv.observe(VoiceKeys.SPEAK_ON_HEADPHONES, Boolean.serializer(), false)
        .stateIn(viewModelScope, started, false)
    val handsFree: StateFlow<Boolean> = kv.observe(VoiceKeys.HANDS_FREE_REVISION, Boolean.serializer(), false)
        .stateIn(viewModelScope, started, false)
    val wakePhrase: StateFlow<Boolean> = kv.observe(VoiceKeys.WAKE_PHRASE, Boolean.serializer(), false)
        .stateIn(viewModelScope, started, false)
    val headphoneMap: StateFlow<Map<String, String>> = kv.observe(VoiceKeys.HEADPHONE_MAP)
        .map { headphoneMapOf(it) }
        .stateIn(viewModelScope, started, VoiceKeys.DEFAULT_HEADPHONE_MAP)

    fun setName(value: String) = put(VoiceKeys.NAME, JsonPrimitive(value))

    fun setListen(value: String) = put(VoiceKeys.LISTEN_LANGUAGE, JsonPrimitive(value))

    fun setSpeed(value: Float) = put(VoiceKeys.SPEED, JsonPrimitive(value))

    fun setSpeakOnHeadphones(value: Boolean) = put(VoiceKeys.SPEAK_ON_HEADPHONES, JsonPrimitive(value))

    fun setHandsFree(value: Boolean) = put(VoiceKeys.HANDS_FREE_REVISION, JsonPrimitive(value))

    fun setWakePhrase(value: Boolean) = put(VoiceKeys.WAKE_PHRASE, JsonPrimitive(value))

    fun setHeadphone(gesture: String, action: String) {
        val map = headphoneMap.value.toMutableMap()
        map[gesture] = action
        put(VoiceKeys.HEADPHONE_MAP, JsonObject(map.mapValues { JsonPrimitive(it.value) }))
    }

    fun testVoice() {
        val language = name.value
        val sample = if (language == "te-IN") "నమస్కారం. నేను మీ చదువుకు సహాయం చేస్తాను." else "Hello. This is how I will read your notes and answers."
        tts.speak(sample, language = language, rate = speed.value)
    }

    private fun put(key: String, value: JsonElement) {
        viewModelScope.launch { kv.put(key, value) }
    }
}

package com.naveen.civilscompanion.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads text aloud with the tablet's own speech engine (works offline). Shared by the Reader, tutor answers, Telugu, ... */
@Singleton
class TtsSpeaker @Inject constructor(@ApplicationContext private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<() -> Unit>()
    private var currentDone: (() -> Unit)? = null
    private var currentSentence: ((Int) -> Unit)? = null
    private var lastId = ""

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Index of the sentence being read by speakSentences (-1 when idle). */
    private val _sentence = MutableStateFlow(-1)
    val sentence: StateFlow<Int> = _sentence.asStateFlow()

    private fun ensureEngine(then: () -> Unit) {
        if (ready) {
            then()
            return
        }
        waiting.add(then)
        if (engine != null) return
        engine = TextToSpeech(context) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                engine?.setOnUtteranceProgressListener(listener)
                val todo = ArrayList(waiting)
                waiting.clear()
                if (ready) todo.forEach { it() }
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val index = utteranceId?.removePrefix("s")?.toIntOrNull() ?: return
            main.post {
                _speaking.value = true
                _sentence.value = index
                currentSentence?.invoke(index)
            }
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastId) main.post { finish() }
        }

        @Deprecated("Replaced by onError(String, Int)")
        override fun onError(utteranceId: String?) {
            main.post { finish() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { finish() }
        }
    }

    private fun finish() {
        _speaking.value = false
        _sentence.value = -1
        val done = currentDone
        currentDone = null
        currentSentence = null
        done?.invoke()
    }

    /** Reads one piece of text. Replaces anything being read. rate 1.0 is normal speed. */
    fun speak(text: String, language: String = "en-IN", rate: Float = 1f, onDone: () -> Unit = {}) {
        speakSentences(listOf(text), 0, language, rate, onSentence = {}, onDone = onDone)
    }

    /** Reads a list of sentences one after another, starting at startIndex, telling you which one is being read. */
    fun speakSentences(
        sentences: List<String>,
        startIndex: Int = 0,
        language: String = "en-IN",
        rate: Float = 1f,
        onSentence: (Int) -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        ensureEngine {
            val tts = engine ?: return@ensureEngine
            tts.stop()
            currentDone = onDone
            currentSentence = onSentence
            val locale = Locale.forLanguageTag(language)
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH)
            }
            tts.setSpeechRate(rate)
            val list = sentences.drop(startIndex.coerceAtLeast(0))
            if (list.isEmpty()) {
                finish()
                return@ensureEngine
            }
            lastId = "s${startIndex + list.lastIndex}"
            list.forEachIndexed { i, text ->
                tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_ADD, null, "s${startIndex + i}")
            }
        }
    }

    fun stop() {
        main.post {
            engine?.stop()
            if (_speaking.value || currentDone != null) {
                _speaking.value = false
                _sentence.value = -1
                currentDone = null
                currentSentence = null
            }
        }
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
    }

}

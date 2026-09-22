package com.naveen.civilscompanion.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

sealed interface VoiceEvent {
    data object Listening : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val message: String) : VoiceEvent
}

/**
 * Speech to text with the tablet's recogniser (offline when the language pack is installed). One listening
 * round ends after a pause; call listen() again to keep going. Needs the RECORD_AUDIO permission
 * (see ui/common/Permissions.kt). Collect the flow to start; cancelling the collection stops listening.
 *
 * If the offline pack for the requested language is missing, the tablet reports error 13 (ERROR_LANGUAGE_UNAVAILABLE)
 * or 12 (ERROR_LANGUAGE_NOT_SUPPORTED) before the owner has even said anything. When that happens on an
 * offline attempt, this class quietly retries once online instead of surfacing a confusing "code 13" message;
 * it only reports an error if that second attempt also fails (or there was no offline attempt to fall back from).
 */
@Singleton
class VoiceInput @Inject constructor(@ApplicationContext private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun recognizeIntent(language: String, offline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)

    fun listen(language: String = "en-IN", preferOffline: Boolean = true): Flow<VoiceEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(VoiceEvent.Failed("Speech recognition is not available on this tablet."))
            close()
            return@callbackFlow
        }
        var recognizer: SpeechRecognizer? = null
        var triedOnline = !preferOffline // already starting online, so there is no further fallback

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.Listening)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val languageMissing = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                if (!triedOnline && languageMissing) {
                    triedOnline = true
                    runCatching { recognizer?.destroy() }
                    val retry = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = retry
                    retry.setRecognitionListener(this)
                    retry.startListening(recognizeIntent(language, offline = false))
                    return
                }
                trySend(VoiceEvent.Failed(errorText(error, language, offlineFallbackFailed = triedOnline && preferOffline)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                trySend(VoiceEvent.Final(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(VoiceEvent.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        val first = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = first
        first.setRecognitionListener(listener)
        first.startListening(recognizeIntent(language, offline = preferOffline))

        awaitClose {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.destroy() }
        }
    }.flowOn(Dispatchers.Main)

    private fun errorText(code: Int, language: String, offlineFallbackFailed: Boolean): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            if (offlineFallbackFailed) {
                "This tablet has no offline voice pack for $language, and online voice also failed (check your internet). " +
                    "Settings > System > Languages > On-device speech recognition, and add that language; or turn on Wi-Fi and try again."
            } else {
                "This tablet does not support $language for speech recognition. Try a different language in Voice settings."
            }
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Offline voice is not installed and there is no internet for online voice. Install the offline pack in " +
                "Settings > System > Languages > On-device speech recognition, or connect to Wi-Fi."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech engine is busy. Try again."
        else -> "Could not understand (code $code)."
    }
}

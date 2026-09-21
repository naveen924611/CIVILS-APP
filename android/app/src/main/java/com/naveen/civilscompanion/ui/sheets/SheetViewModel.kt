package com.naveen.civilscompanion.ui.sheets

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Sheet
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.speech.TtsSpeaker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SheetPage(
    val loading: Boolean = true,
    val topic: Topic? = null,
    val sheet: Sheet? = null,
    val listening: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/** One revision sheet: read it, listen to it (the tablet's own voice, works offline), save or share it as a PDF. */
@HiltViewModel
class SheetViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val kv: KvRepository,
    private val tts: TtsSpeaker,
    private val pdf: PdfDownloader,
) : ViewModel() {
    private val _state = MutableStateFlow(SheetPage())
    val state: StateFlow<SheetPage> = _state.asStateFlow()

    private val _pdf = MutableSharedFlow<Uri>(extraBufferCapacity = 1)

    /** The downloaded PDF, ready to hand to the share sheet. */
    val pdfReady: SharedFlow<Uri> = _pdf.asSharedFlow()

    private var loadedId: String? = null

    fun load(topicId: String) {
        if (loadedId == topicId) return
        loadedId = topicId
        viewModelScope.launch {
            val topic = store.get(Tables.Topics, topicId)
            _state.value = _state.value.copy(topic = topic)
            store.observe(Tables.Sheets, RecordQuery(k1 = topicId)).collectLatest { list ->
                _state.value = _state.value.copy(loading = false, sheet = list.firstOrNull())
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Asks the server to make or update the sheet (needs the internet; queued when offline). */
    fun remake() {
        val id = loadedId ?: return
        viewModelScope.launch {
            jobs.enqueue("revision_sheet", jobPayload("topic_id" to id))
            _state.value = _state.value.copy(message = "The sheet will be made or updated when you are online.")
        }
    }

    fun listen() {
        val sheet = _state.value.sheet ?: return
        val script = (sheet.sections["script"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { sheet.contentMd }
        val pieces = SheetLogic.sentences(script)
        if (pieces.isEmpty()) return
        val speed = kv.get("voice.speed", Float.serializer(), 1f)
        _state.value = _state.value.copy(listening = true)
        tts.speakSentences(pieces, 0, "en-IN", speed, onSentence = {}, onDone = { _state.value = _state.value.copy(listening = false) })
    }

    fun stopListening() {
        tts.stop()
        _state.value = _state.value.copy(listening = false)
    }

    /** Downloads the PDF from the server, then [pdfReady] fires. */
    fun preparePdf() {
        val sheet = _state.value.sheet ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val file = pdf.sheet(sheet.id)
            if (file == null) {
                _state.value = _state.value.copy(busy = false, message = "The PDF needs the internet. Connect and try again.")
            } else {
                _state.value = _state.value.copy(busy = false)
                _pdf.tryEmit(pdf.uriOf(file))
            }
        }
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }
}

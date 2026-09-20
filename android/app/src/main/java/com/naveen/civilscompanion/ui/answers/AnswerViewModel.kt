package com.naveen.civilscompanion.ui.answers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.AnswerSubmission
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TimerUi(val running: Boolean = false, val elapsedSec: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnswerViewModel @Inject constructor(
    private val store: RecordStore,
    private val uploader: AnswerUploader,
) : ViewModel() {

    private val id = MutableStateFlow("")

    val answer: StateFlow<AnswerSubmission?> = id.flatMapLatest { value ->
        if (value.isEmpty()) flowOf(null) else store.observeOne(Tables.Answers, value)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while photos wait for the internet before they can be sent. */
    val uploadPending: StateFlow<Boolean> = id.flatMapLatest { value ->
        flow {
            while (true) {
                emit(value.isNotEmpty() && uploader.isUploadPending(value))
                delay(3_000)
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _photos = MutableStateFlow<List<File>>(emptyList())
    val photos: StateFlow<List<File>> = _photos.asStateFlow()

    private val _typed = MutableStateFlow("")
    val typed: StateFlow<String> = _typed.asStateFlow()

    /** 0 = written on paper (photos), 1 = typed here. */
    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _timer = MutableStateFlow(TimerUi())
    val timer: StateFlow<TimerUi> = _timer.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var tickJob: Job? = null
    private var saveJob: Job? = null

    fun load(answerId: String) {
        if (id.value == answerId) return
        id.value = answerId
        _photos.value = uploader.photos(answerId)
        _typed.value = uploader.typedText(answerId)
    }

    fun setTab(index: Int) {
        _tab.value = index
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun micOrCameraDenied() {
        _notice.value = "The camera is off. Turn it on in the tablet's app settings, or choose a picture from the gallery."
    }

    // ------------------------------------------------------------------ timer

    fun toggleTimer() {
        if (_timer.value.running) {
            tickJob?.cancel()
            _timer.value = _timer.value.copy(running = false)
            return
        }
        _timer.value = _timer.value.copy(running = true)
        tickJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _timer.value = _timer.value.copy(elapsedSec = _timer.value.elapsedSec + 1)
            }
        }
    }

    fun resetTimer() {
        tickJob?.cancel()
        _timer.value = TimerUi()
    }

    // ------------------------------------------------------------------ writing

    fun addPhotoFile(file: File) {
        val answerId = id.value
        viewModelScope.launch {
            uploader.addPhoto(answerId, file)
            _photos.value = uploader.photos(answerId)
        }
    }

    fun addPhotoUri(uri: Uri) {
        val answerId = id.value
        viewModelScope.launch {
            if (uploader.addPhoto(answerId, uri) == null) _notice.value = "That picture could not be read."
            _photos.value = uploader.photos(answerId)
        }
    }

    fun removePhoto(file: File) {
        uploader.removePhoto(file)
        _photos.value = uploader.photos(id.value)
    }

    fun setTyped(text: String) {
        _typed.value = text
        val answerId = id.value
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            uploader.saveTypedText(answerId, text)
        }
    }

    /** Sends the answer (photos or typed text) for feedback. It is queued when the tablet is offline. */
    fun submit() {
        val answerId = id.value
        if (answerId.isEmpty()) return
        val typedMode = _tab.value == 1
        val text = _typed.value.trim()
        if (typedMode && !AnswerLogic.canSendTyped(text)) {
            _notice.value = "Please write your answer first (at least ${AnswerLogic.MIN_TYPED_WORDS} words)."
            return
        }
        if (!typedMode && _photos.value.isEmpty()) {
            _notice.value = "Please add a photo of your answer first."
            return
        }
        tickJob?.cancel()
        _timer.value = _timer.value.copy(running = false)
        viewModelScope.launch {
            if (typedMode) uploader.submitTyped(answerId, text) else uploader.submitPhotos(answerId)
            store.update(Tables.Answers, answerId) { it.copy(status = "queued") }
        }
    }

    /** After a failure: the photos are already on the server, so ask for feedback once more. */
    fun checkAgain() {
        val answerId = id.value
        viewModelScope.launch {
            uploader.requestAgain(answerId)
            store.update(Tables.Answers, answerId) { it.copy(status = "queued") }
        }
    }

    /** After a failure: go back to writing. */
    fun writeAgain() {
        val answerId = id.value
        viewModelScope.launch {
            store.update(Tables.Answers, answerId) { it.copy(status = "draft") }
            _photos.value = uploader.photos(answerId)
        }
    }
}

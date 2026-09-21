package com.naveen.civilscompanion.ui.compilation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Compilation
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.util.CaptureFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** A PDF that was downloaded and is ready to open or share. */
data class PdfReady(val uri: Uri, val share: Boolean)

@HiltViewModel
class CompilationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val api: CompilationApi,
) : ViewModel() {

    val list: StateFlow<List<Compilation>> = store.observe(Tables.Compilations, RecordQuery(limit = 240))
        .map { rows -> CompilationLogic.newestFirst(rows.filter { !it.deleted }) { it.month } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while a "make one now" request is waiting or running. */
    val building: StateFlow<Boolean> = jobs.observeRecent("compilation_build", 5)
        .map { rows -> rows.any { it.status == "queued" || it.status == "running" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pdf = MutableSharedFlow<PdfReady>(extraBufferCapacity = 1)
    val pdf: SharedFlow<PdfReady> = _pdf.asSharedFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** Asks the server to compile a month (works offline: the request waits for the internet). month = YYYY-MM. */
    fun build(month: String) {
        if (!CompilationLogic.isValidMonth(month)) return
        viewModelScope.launch {
            jobs.enqueue("compilation_build", jobPayload("month" to month))
            _message.value = "Asked the server to prepare ${CompilationLogic.monthLabel(month)}. It appears here when it is ready."
        }
    }

    /** Downloads the PDF of a month, then tells the screen to open or share it. Needs the internet. */
    fun fetchPdf(item: Compilation, share: Boolean) {
        if (_busy.value) return
        if (item.pdfPath.isNullOrBlank()) {
            _message.value = "There is no PDF for this month. You can still read or share the text."
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, "exports").apply { mkdirs() }
                    val target = File(dir, CompilationLogic.pdfFileName(item.month))
                    api.pdf(item.id).use { body ->
                        body.byteStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                    }
                    target
                }
                _pdf.tryEmit(PdfReady(CaptureFiles.uriFor(context, file), share))
            } catch (e: IOException) {
                _message.value = "You need the internet to get the PDF."
            } catch (e: HttpException) {
                _message.value = if (e.code() == 404) "The PDF is not ready on the server yet." else "Could not get the PDF. Please try again later."
            } catch (e: IllegalArgumentException) {
                _message.value = "Could not prepare the PDF for sharing."
            } finally {
                _busy.value = false
            }
        }
    }

    fun today(): String = TimeUtil.today()
}

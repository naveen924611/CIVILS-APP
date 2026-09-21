package com.naveen.civilscompanion.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.AudioStore
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.sync.SyncScheduler
import com.naveen.civilscompanion.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.HttpException

data class StorageUi(
    val usage: UsageDto? = null,
    val tabletAudioBytes: Long = 0,
    val ai: AiUsageDto? = null,
    val backups: List<BackupDto> = emptyList(),
    val limitGb: Int = StorageLogic.DEFAULT_LIMIT_GB,
    val busy: String? = null,
    val message: String? = null,
    val exportFile: File? = null,
    val lastSync: String? = null,
)

/** Storage, backups, export and AI usage (spec 6.8). Everything here needs the server except the limit and Sync now. */
@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: StorageApi,
    private val kv: KvRepository,
    private val audio: AudioStore,
    private val prefs: Prefs,
) : ViewModel() {
    private val _state = MutableStateFlow(
        StorageUi(limitGb = readLimit(), lastSync = prefs.lastSync),
    )
    val state: StateFlow<StorageUi> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun readLimit(): Int {
        val p = kv.get(StorageLogic.KEY_LIMIT) as? JsonPrimitive ?: return StorageLogic.DEFAULT_LIMIT_GB
        val n = p.intOrNull ?: p.doubleOrNull?.toInt() ?: return StorageLogic.DEFAULT_LIMIT_GB
        return n.coerceIn(StorageLogic.MIN_LIMIT_GB, StorageLogic.MAX_LIMIT_GB)
    }

    fun refresh() {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { folderBytes(File(context.filesDir, "audio")) }
            _state.update { it.copy(tabletAudioBytes = local, lastSync = prefs.lastSync, limitGb = readLimit()) }
            val text = try {
                val usage = api.usage()
                val backups = runCatching { api.backups().backups }.getOrDefault(emptyList())
                val ai = runCatching { api.aiUsage() }.getOrNull()
                _state.update { it.copy(usage = usage, backups = backups, ai = ai) }
                null
            } catch (e: IOException) {
                "Cannot reach the server, so the numbers may be old."
            } catch (e: HttpException) {
                "The server did not answer (${e.code()})."
            }
            if (text != null) _state.update { it.copy(message = text) }
        }
    }

    fun changeLimit(delta: Int) {
        val next = StorageLogic.stepLimit(_state.value.limitGb, delta)
        _state.update { it.copy(limitGb = next) }
        viewModelScope.launch { kv.put(StorageLogic.KEY_LIMIT, JsonPrimitive(next)) }
    }

    fun syncNow() {
        SyncScheduler.syncNow(context)
        viewModelScope.launch { WidgetUpdater.refresh(context) }
        _state.update { it.copy(message = "Sync started. It runs as soon as there is a network.") }
    }

    /** Deletes audio older than 60 days on the server and on this tablet. */
    fun deleteOldAudio(days: Int = 60) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Deleting old audio…", message = null) }
            withContext(Dispatchers.IO) { audio.deleteOlderThan(days) }
            val text = try {
                val r = api.cleanup(CleanupBody(days))
                "Deleted ${r.deletedFiles} old audio file(s) on the server (${StorageLogic.bytes(r.freedBytes)}) and cleaned this tablet."
            } catch (e: IOException) {
                "Old audio on this tablet was cleaned. The server could not be reached, so its old audio is still there."
            } catch (e: HttpException) {
                "The server refused (${e.code()}). Old audio on this tablet was cleaned."
            }
            _state.update { it.copy(busy = null, message = text) }
            refresh()
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Making a backup…", message = null) }
            val text = try {
                val made = api.runBackup()
                "Backup saved on the server (${StorageLogic.bytes(made.bytes)})."
            } catch (e: IOException) {
                "Cannot reach the server."
            } catch (e: HttpException) {
                if (e.code() == 409) "Backups need the server's SQLite database." else "The backup did not work (${e.code()})."
            }
            _state.update { it.copy(busy = null, message = text) }
            refresh()
        }
    }

    /** Downloads the zip of all data into the app's export folder; the screen then offers to share or save it. */
    fun exportData() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Preparing your data…", message = null, exportFile = null) }
            val result: Pair<File?, String?> = try {
                val file = withContext(Dispatchers.IO) { downloadExport() }
                file to null
            } catch (e: IOException) {
                null to "Export needs the server. Please try again when you are online."
            } catch (e: HttpException) {
                null to if (e.code() == 429) "Please wait a few minutes before exporting again." else "Export did not work (${e.code()})."
            }
            _state.update { it.copy(busy = null, message = result.second, exportFile = result.first) }
        }
    }

    fun exportHandled() = _state.update { it.copy(exportFile = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun downloadExport(): File {
        val folder = File(context.filesDir, "exports").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() } // keep only the newest export
        val target = File(folder, "civils-companion-${TimeUtil.today()}.zip")
        api.export().use { body ->
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        return target
    }

    private fun folderBytes(folder: File): Long =
        folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

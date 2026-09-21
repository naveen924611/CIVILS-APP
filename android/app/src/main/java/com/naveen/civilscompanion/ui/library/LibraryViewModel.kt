package com.naveen.civilscompanion.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.DocPage
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.LibraryItem
import com.naveen.civilscompanion.data.model.Material
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.reader.ReadingPosition
import com.naveen.civilscompanion.reader.groupBySubject
import com.naveen.civilscompanion.reader.snippetAround
import com.naveen.civilscompanion.reader.titleMatches
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab { Mine, Recommended }

data class MaterialRow(val material: Material, val document: LibDocument?, val onList: LibraryItem?)

data class SearchHit(val documentId: String, val title: String, val page: Int, val snippet: String)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.Mine,
    val query: String = "",
    val groups: List<Pair<String, List<LibDocument>>> = emptyList(),
    val documentCount: Int = 0,
    val hits: List<SearchHit> = emptyList(),
    val official: List<MaterialRow> = emptyList(),
    val books: List<MaterialRow> = emptyList(),
    val libraryList: List<LibraryItem> = emptyList(),
    val libraryDay: LibraryDay = LibraryDay(),
    val pending: List<PendingScan> = emptyList(),
    val subjects: List<Topic> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

private data class Local(val tab: LibraryTab = LibraryTab.Mine, val query: String = "", val busy: Boolean = false, val message: String? = null)

private data class Data(
    val docs: List<LibDocument>,
    val topics: List<Topic>,
    val materials: List<Material>,
    val list: List<LibraryItem>,
    val pending: List<PendingScan>,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val store: RecordStore,
    private val repo: LibraryRepository,
    private val kv: KvRepository,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    private val data: Flow<Data> = combine(
        store.observe(Tables.Documents, RecordQuery(order = Order.NewestFirst)),
        store.observe(Tables.Topics, RecordQuery(order = Order.NumberAsc)),
        store.observe(Tables.Materials, RecordQuery(order = Order.TextAZ)),
        store.observe(Tables.LibraryList, RecordQuery(order = Order.TextAZ)),
        repo.observePending(),
    ) { docs, topics, materials, list, pending -> Data(docs, topics, materials, list, pending) }

    private val hitPages: Flow<List<DocPage>> = local.map { it.query.trim() }.distinctUntilChanged().debounce(250)
        .flatMapLatest { q ->
            if (q.length < 2) flowOf(emptyList<DocPage>()) else store.observe(Tables.DocPages, RecordQuery(contains = q, limit = 40))
        }

    private val libraryDay: Flow<LibraryDay> = kv.observe(LIBRARY_DAY_KEY, LibraryDay.serializer(), LibraryDay())

    val state: StateFlow<LibraryUiState> = combine(data, local, hitPages, libraryDay) { d, l, pages, day ->
        val byId = d.topics.associateBy { it.id }
        val docs = d.docs.filter { !it.deleted && titleMatches(it.title, l.query) }
        val docsById = d.docs.associateBy { it.id }
        val query = l.query.trim()
        LibraryUiState(
            tab = l.tab,
            query = l.query,
            groups = groupBySubject(docs) { subjectOf(it, byId, d.materials) },
            documentCount = d.docs.size,
            hits = pages.mapNotNull { p ->
                docsById[p.documentId]?.takeIf { !it.deleted }?.let { SearchHit(it.id, it.title, p.page, snippetAround(p.text, query)) }
            },
            official = d.materials.filter { it.kind != "book" }.map { m -> materialRow(m, d) },
            books = d.materials.filter { it.kind == "book" }.map { m -> materialRow(m, d) },
            libraryList = d.list.sortedWith(compareBy({ it.done }, { it.book.lowercase() })),
            libraryDay = day,
            pending = d.pending,
            subjects = d.topics.filter { it.level == 1 }.distinctBy { it.title },
            busy = l.busy,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private fun materialRow(m: Material, d: Data): MaterialRow = MaterialRow(
        material = m,
        document = if (m.url.isBlank()) null else d.docs.firstOrNull { !it.deleted && it.sourceUrl == m.url },
        onList = d.list.firstOrNull { it.book == m.title },
    )

    /** A document's subject: its topic's subject (or paper) name, or the subject of the recommended item it came from. */
    private fun subjectOf(doc: LibDocument, topics: Map<String, Topic>, materials: List<Material>): String {
        val topicId = doc.topicId
        if (topicId != null) {
            var t: Topic? = topics[topicId]
            var guard = 0
            while (t != null && t.level > 1 && guard++ < 8) t = t.parentId?.let { topics[it] }
            if (t != null) return t.title
        }
        if (doc.sourceUrl.isNotBlank()) {
            materials.firstOrNull { it.url == doc.sourceUrl }?.let { return it.subject }
        }
        return ""
    }

    // ------------------------------------------------------------------ actions

    fun selectTab(tab: LibraryTab) = local.update { it.copy(tab = tab) }

    fun setQuery(text: String) = local.update { it.copy(query = text) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun tell(text: String) = local.update { it.copy(message = text) }

    private fun say(text: String) = local.update { it.copy(message = text, busy = false) }

    private fun work(block: suspend () -> SendResult) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch { say(block().message) }
    }

    fun uploadPdf(uri: Uri) = work { repo.uploadPdf(uri) }

    fun uploadImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) work { repo.uploadImages(uris) }
    }

    fun retry(doc: LibDocument) = work { repo.retry(doc.id) }

    fun download(material: Material) = work { repo.downloadMaterial(material.key) }

    /** Opens a search result: remembers the page, then [go] shows the reader. */
    fun openHit(hit: SearchHit, go: () -> Unit) {
        viewModelScope.launch {
            store.update(Tables.Documents, hit.documentId) { it.copy(readingPosition = ReadingPosition(hit.page, 0).toJson()) }
            go()
        }
    }

    fun sendWaitingScans() = work { repo.flushScans() ?: SendResult(true, "Nothing is waiting.") }

    fun deleteDocument(doc: LibDocument) {
        viewModelScope.launch {
            store.delete(Tables.Documents, doc.id)
            say("Removed \"${doc.title}\".")
        }
    }

    fun rename(doc: LibDocument, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { store.update(Tables.Documents, doc.id) { it.copy(title = clean) } }
    }

    fun moveToSubject(doc: LibDocument, topicId: String?) {
        viewModelScope.launch { store.update(Tables.Documents, doc.id) { it.copy(topicId = topicId) } }
    }

    fun addToLibraryList(material: Material) {
        viewModelScope.launch {
            val exists = store.list(Tables.LibraryList).any { it.book == material.title }
            if (!exists) store.save(Tables.LibraryList, LibraryItem(id = TimeUtil.newId(), book = material.title, why = material.why))
        }
    }

    fun removeFromLibraryList(item: LibraryItem) {
        viewModelScope.launch { store.delete(Tables.LibraryList, item.id) }
    }

    fun toggleDone(item: LibraryItem) {
        viewModelScope.launch { store.update(Tables.LibraryList, item.id) { it.copy(done = !it.done) } }
    }

    /** Called when the screen opens: photos taken earlier are sent if there is internet. */
    fun sendWaitingQuietly() {
        viewModelScope.launch { repo.flushScans() }
    }
}

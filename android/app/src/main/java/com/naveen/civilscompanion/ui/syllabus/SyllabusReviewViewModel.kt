package com.naveen.civilscompanion.ui.syllabus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.SyllabusImport
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

data class ReviewState(
    val loading: Boolean = true,
    val imp: SyllabusImport? = null,
    val tree: List<SNode> = emptyList(),
    val collapsed: Set<Int> = emptySet(),
    val examFilter: String? = null, // null = keep both exams
    val mergeWithExisting: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val result: ApproveResult? = null,
    val initialized: Boolean = false, // the tree was read from the import (later syncs must not overwrite the owner's edits)
    val edited: Boolean = false,
) {
    /** How many topics would be created with the current filter. */
    val approveCount: Int get() = SyllabusTree.count(SyllabusTree.keepExam(tree, examFilter))
}

/** Checking and approving an imported syllabus (spec 6.20): accept, edit, merge or split topics. */
@HiltViewModel
class SyllabusReviewViewModel @Inject constructor(
    private val store: RecordStore,
    private val api: SyllabusApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()
    private var loadedId: String? = null

    fun load(importId: String) {
        if (loadedId == importId) return
        loadedId = importId
        viewModelScope.launch {
            store.observeOne(Tables.SyllabusImports, importId).collect { imp ->
                _state.update { st ->
                    val tree = imp?.treeJson
                    val fresh = imp != null && !st.initialized && imp.status != "processing" && tree is JsonArray
                    st.copy(
                        loading = false,
                        imp = imp,
                        tree = if (fresh) SyllabusTree.parse(tree) else st.tree,
                        initialized = st.initialized || fresh,
                    )
                }
            }
        }
    }

    private fun edit(change: (List<SNode>) -> List<SNode>) {
        _state.update { it.copy(tree = change(it.tree), edited = true, message = null) }
    }

    fun rename(key: Int, title: String) = edit { SyllabusTree.rename(it, key, title) }
    fun remove(key: Int) = edit { SyllabusTree.remove(it, key) }
    fun addChild(parentKey: Int?, title: String) = edit { SyllabusTree.addChild(it, parentKey, title) }
    fun moveUp(key: Int) = edit { SyllabusTree.moveUp(it, key) }
    fun moveDown(key: Int) = edit { SyllabusTree.moveDown(it, key) }
    fun mergeUp(key: Int) = edit { SyllabusTree.mergeWithPrevious(it, key) }
    fun split(key: Int, titles: List<String>) = edit { SyllabusTree.split(it, key, titles) }
    fun setTags(key: Int, tags: List<String>) = edit { SyllabusTree.setTags(it, key, tags) }

    fun toggle(key: Int) {
        _state.update { it.copy(collapsed = if (key in it.collapsed) it.collapsed - key else it.collapsed + key) }
    }

    fun setExamFilter(value: String?) {
        _state.update { it.copy(examFilter = value) }
    }

    fun setMerge(value: Boolean) {
        _state.update { it.copy(mergeWithExisting = value) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Saves the owner's edits on the server without approving (so he can come back later). */
    fun saveDraft() {
        val st = _state.value
        val imp = st.imp ?: return
        if (st.busy || st.tree.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val res = apiCall { api.saveTree(imp.id, TreeBody(SyllabusTree.toJson(st.tree), null)) }
            _state.update {
                it.copy(
                    busy = false,
                    edited = if (res.isSuccess) false else it.edited,
                    message = if (res.isSuccess) "Saved. You can finish later." else describeError(res.exceptionOrNull() ?: IllegalStateException()),
                )
            }
        }
    }

    /** Approves: the server creates the topics (and an empty note for each), then they sync down to the tablet. */
    fun approve() {
        val st = _state.value
        val imp = st.imp ?: return
        if (st.busy) return
        if (st.approveCount == 0) {
            _state.update { it.copy(message = "There is nothing to approve. Add topics or choose all exams.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val body = ApproveBody(st.examFilter, st.mergeWithExisting, SyllabusTree.toJson(st.tree))
            val res = apiCall { api.approve(imp.id, body) }
            val done = res.getOrNull()
            if (done != null) SyncScheduler.syncNow(context)
            _state.update {
                it.copy(
                    busy = false,
                    result = done,
                    edited = if (done != null) false else it.edited,
                    message = if (done != null) null else describeError(res.exceptionOrNull() ?: IllegalStateException()),
                )
            }
        }
    }
}

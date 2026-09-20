package com.naveen.civilscompanion.ui.syllabus

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.SyllabusImport
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.sync.SyncScheduler
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val outlines: List<SyllabusImport> = emptyList(),
    val approvedNow: Set<String> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
)

/** First-run step "Approve your syllabus" (spec 6.19): use the starter outlines as they are; edit them later in the syllabus map. */
@HiltViewModel
class SyllabusSetupViewModel @Inject constructor(
    store: RecordStore,
    private val api: SyllabusApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val local = MutableStateFlow(SetupState())

    val state: StateFlow<SetupState> = combine(store.observe(Tables.SyllabusImports), local) { imports, mine ->
        mine.copy(outlines = imports.filter { it.status == "pending" || it.status == "approved" }.sortedBy { it.title })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupState())

    fun approve(imp: SyllabusImport) {
        if (local.value.busy) return
        viewModelScope.launch { approveOne(imp) }
    }

    fun approveAll() {
        if (local.value.busy) return
        val todo = state.value.outlines.filter { it.status == "pending" && it.id !in local.value.approvedNow }
        viewModelScope.launch { todo.forEach { approveOne(it) } }
    }

    private suspend fun approveOne(imp: SyllabusImport) {
        local.update { it.copy(busy = true, message = null) }
        val res = apiCall { api.approve(imp.id, ApproveBody(null, true, null)) }
        val ok = res.isSuccess
        if (ok) SyncScheduler.syncNow(context)
        local.update {
            it.copy(
                busy = false,
                approvedNow = if (ok) it.approvedNow + imp.id else it.approvedNow,
                message = if (ok) null else describeError(res.exceptionOrNull() ?: IllegalStateException()),
            )
        }
    }
}

@Composable
fun SyllabusSetupStep(onNext: () -> Unit, vm: SyllabusSetupViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pending = s.outlines.filter { it.status == "pending" && it.id !in s.approvedNow }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Approve your syllabus", style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
        Text(
            "Your topics come from a syllabus outline. The outlines below were prepared for you, but they are NOT checked against " +
                "the official syllabus yet: please compare them with the PDFs on psc.ap.gov.in and upsc.gov.in. " +
                "You can edit, merge or split any topic later in Notes, under Syllabus map, or import your own syllabus there.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.muted,
        )
        if (s.outlines.isEmpty()) {
            Text(
                "The outlines arrive with the first sync (needs internet). You can approve them later from Notes, under Syllabus map.",
                style = MaterialTheme.typography.bodyMedium,
                color = Cc.colors.ink,
            )
        }
        s.outlines.forEach { imp ->
            val done = imp.status == "approved" || imp.id in s.approvedNow
            CcCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(imp.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                        if (imp.note.isNotBlank()) Text(imp.note, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    }
                    if (done) Pill("Approved", tone = 1) else BigButton("Use this outline", onClick = { vm.approve(imp) }, enabled = !s.busy)
                }
            }
        }
        s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onDangerTint) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pending.size > 1) BigButton("Use all outlines", onClick = vm::approveAll, filled = false, enabled = !s.busy)
            BigButton(if (s.outlines.any { it.id in s.approvedNow || it.status == "approved" }) "Continue" else "Skip for now", onClick = onNext)
        }
    }
}

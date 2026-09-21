package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel

/** What the owner has opened inside the Telugu screen. */
sealed interface OpenPractice {
    /** All of today's words as flash cards. */
    data class Words(val itemIds: List<String>) : OpenPractice

    /** One passage, translation or letter/essay. */
    data class One(val itemId: String) : OpenPractice
}

/** Telugu paper practice (spec 6.22): today's set, and a separate progress tab. Works offline from the synced items. */
@Composable
fun TeluguScreen(nav: NavHostController, vm: TeluguViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<OpenPractice?>(null) }
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 24.dp, vertical = 20.dp)) {
        val current = open
        if (current == null) {
            ScreenTitle("Telugu practice", subtitle = "${ui.minutes} minutes a day. Small steps, every day.")
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("Today", tab == 0) { tab = 0 }
                TabChip("Progress", tab == 1) { tab = 1 }
                TabChip("My writing", tab == 2) { tab = 2 }
            }
            when {
                !ui.loaded -> Unit
                ui.items.isEmpty() -> EmptyState(
                    "No Telugu practice yet",
                    "The practice items arrive from the server at the next sync. Connect to the internet and open Settings > Sync.",
                )
                tab == 0 -> TodayTab(ui, onOpen = { open = it })
                tab == 1 -> TeluguProgressTab(ui)
                else -> WritingHistoryTab(ui, onOpen = { open = OpenPractice.One(it) })
            }
        } else {
            OpenedPractice(current, ui, vm, onClose = { open = null })
        }
    }
}

@Composable
private fun OpenedPractice(current: OpenPractice, ui: TeluguUi, vm: TeluguViewModel, onClose: () -> Unit) {
    when (current) {
        is OpenPractice.Words -> {
            val cards = current.itemIds.mapNotNull { id -> ui.items.firstOrNull { it.id == id } }
            VocabSession(cards, vm, onClose)
        }
        is OpenPractice.One -> {
            val item = ui.items.firstOrNull { it.id == current.itemId }
            if (item == null) {
                EmptyState("Not found", "This item is no longer available.")
                BigButton("Back", onClose, filled = false)
            } else when (item.kind) {
                "passage" -> PassageScreen(item, vm, onClose)
                else -> WritingScreen(item, ui, vm, onClose)
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Cc.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary else c.rail)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onPrimary else c.ink)
    }
}

@Composable
private fun TodayTab(ui: TeluguUi, onOpen: (OpenPractice) -> Unit) {
    val words = ui.today.filter { it.item.kind == "vocab" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val total = ui.today.size
        val done = ui.today.count { it.done }
        Text(
            if (total == 0) "Nothing planned. Raise the minutes in Settings to get practice." else "$done of $total done today",
            style = MaterialTheme.typography.titleMedium,
            color = Cc.colors.ink,
        )
        if (words.isNotEmpty()) {
            val left = words.count { !it.done }
            CcCard(Modifier.fillMaxWidth()) {
                SectionLabel(TeluguPlan.kindLabel("vocab"))
                Text("${words.size} words to learn", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    if (left == 0) "All done. Well done!" else "$left still to go. Flash cards with sound.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
                val todo = words.filter { !it.done }.ifEmpty { words }
                BigButton(if (left == 0) "Practise again" else "Start words", { onOpen(OpenPractice.Words(todo.map { it.item.id })) })
            }
        }
        ui.today.filter { it.item.kind != "vocab" }.forEach { t -> TaskCard(t, onOpen) }
        GeneralPracticeNote(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TaskCard(t: TodayItem, onOpen: (OpenPractice) -> Unit) {
    val title: String
    val detail: String
    when (t.item.kind) {
        "passage" -> {
            val p = TeluguContent.passage(t.item.content)
            title = p.titleEn.ifBlank { p.title }
            detail = "Read the passage and answer ${p.questions.size} questions."
        }
        "translation" -> {
            val tr = TeluguContent.translation(t.item.content)
            title = if (tr.toTelugu) "Translate into Telugu" else "Translate into English"
            detail = if (tr.toTelugu) tr.en else tr.te
        }
        else -> {
            val w = TeluguContent.template(t.item.content)
            title = w.titleEn
            detail = w.taskEn
        }
    }
    CcCard(Modifier.fillMaxWidth(), onClick = { onOpen(OpenPractice.One(t.item.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(TeluguPlan.kindLabel(t.item.kind), Modifier.weight(1f))
            if (t.done) Pill("Done " + TeluguPlan.percent(t.score).takeIf { t.score != null }.orEmpty(), tone = 1)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        if (t.item.kind == "translation" && !TeluguContent.translation(t.item.content).toTelugu) {
            TeluguText(detail, size = 20.sp)
        } else {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
    }
}

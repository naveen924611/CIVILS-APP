package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.syllabus.TopicTree

private val TABS = listOf("Notes", "In the news", "Questions", "My doubts", "Sources")

/** Right side of the Notes screen (spec 6.6): title and tags, buttons, and the tabs. */
@Composable
fun NoteDetailPane(nav: NavHostController, s: NotesListState, d: DetailState, vm: NotesViewModel, modifier: Modifier = Modifier) {
    val topic = d.topic
    if (topic == null) {
        Box(modifier) {
            EmptyState(
                if (s.topics.isEmpty()) "No topics yet" else "Choose a topic",
                if (s.topics.isEmpty()) "Approve a syllabus in the Syllabus map first. Then every topic gets its own notes."
                else "Pick a topic on the left to read or write its notes.",
            )
        }
        return
    }
    var tab by rememberSaveable(topic.id) { mutableStateOf(0) }
    var editing by remember(topic.id) { mutableStateOf(false) }
    var reportOpen by remember(topic.id) { mutableStateOf(false) }
    var addOpen by remember(topic.id) { mutableStateOf(false) }
    val note = d.note
    val bodyMd = note?.let { NoteLogic.split(it.contentMd).body }.orEmpty()
    val openAsk = { nav.navigate(Routes.ASK) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(topic.title, style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (topic.paper.isNotBlank()) Pill(topic.paper)
            topic.examTags.forEach { Pill(it, tone = 1) }
            Pill("Importance: ${TopicTree.importanceLabel(topic.importance)}", tone = if (topic.importance >= 7.0) 2 else 0)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (s.speaking) "Stop" else "Listen", onClick = { if (s.speaking) vm.stopListening() else vm.listen(bodyMd) }, filled = false)
            BigButton("Edit", onClick = { tab = 0; editing = true }, filled = false, enabled = note != null)
            BigButton("Make notes", onClick = { vm.makeNotes(topic.id) })
            BigButton("Add to notes", onClick = { addOpen = true }, filled = false)
            BigButton("Report error", onClick = { reportOpen = true }, filled = false, enabled = bodyMd.isNotBlank())
            BigButton("Ask about this", onClick = {
                vm.askAbout(topic)
                openAsk()
            }, filled = false)
        }
        s.message?.let { StatusBanner(it, onDismiss = vm::dismissMessage) }
        d.job?.let { JobLine(it) }
        TabRow(tab, onPick = { tab = it; editing = false })
        when (tab) {
            0 -> NoteBodyTab(d, editing, onEditDone = { editing = false }, vm = vm)
            1 -> NewsTab(d, vm, onAsk = { openAsk() })
            2 -> QuestionsTab(d)
            3 -> DoubtsTab(d, vm, onAsk = { openAsk() })
            else -> SourcesTab(d, onOpenDoc = { nav.navigate(Routes.readDoc(it)) })
        }
        Box(Modifier.height(24.dp))
    }
    if (reportOpen) {
        TextInputDialog(
            title = "Report an error",
            hint = "Tell me what looks wrong. The AI checks it against your own books and pages, and never adds facts from memory.",
            label = "What is wrong?",
            confirm = "Send",
            onConfirm = { vm.reportError(topic.id, it) },
            onDismiss = { reportOpen = false },
        )
    }
    if (addOpen) {
        TextInputDialog(
            title = "Add to notes",
            hint = "Paste or type text (for example from a page you read). Only new points are added; anything already in your note is skipped.",
            label = "Text to add",
            confirm = "Add",
            onConfirm = { vm.addText(topic.id, it) },
            onDismiss = { addOpen = false },
        )
    }
    s.versions?.let { ui ->
        VersionsDialog(
            ui = ui,
            onClose = vm::closeVersions,
            onView = { vm.previewVersion(ui.noteId, it) },
            onRestore = { vm.restoreVersion(ui.noteId, it) },
        )
    }
}

@Composable
private fun TabRow(current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TABS.forEachIndexed { i, label ->
            val chosen = i == current
            Column(
                Modifier.clickable { onPick(i) }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) Cc.colors.primary else Cc.colors.muted,
                )
                Box(Modifier.height(3.dp).fillMaxWidth().background(if (chosen) Cc.colors.primary else Cc.colors.background))
            }
        }
    }
}

@Composable
private fun StatusBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Cc.colors.accentTint).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Cc.colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
    }
}

/** What the newest AI job for this topic is doing or did. */
@Composable
private fun JobLine(job: TopicJob) {
    val what = when (job.mode) {
        "generate" -> "Making notes"
        "fix" -> "Checking your report"
        else -> "Adding text"
    }
    val (text, tone) = when (job.status) {
        "queued" -> "$what: waiting for internet" to 2
        "running" -> "$what: working on it" to 2
        "failed" -> "$what: ${job.error.ifBlank { "it did not work this time" }}" to 3
        else -> "$what: ${job.summary.ifBlank { "done" }}" to 1
    }
    Pill(text, tone = tone)
}

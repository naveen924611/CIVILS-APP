package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.MarkdownText
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.syllabus.TopicTree

/** The "Notes" tab: the note (or the editor), suggestions from new material, and the boxes on the right. */
@Composable
fun NoteBodyTab(
    d: DetailState,
    editing: Boolean,
    onEditDone: () -> Unit,
    vm: NotesViewModel,
) {
    val note = d.note
    val topic = d.topic ?: return
    if (note == null) {
        Text(
            "The note for this topic has not reached the tablet yet. Connect to the internet for a moment, or tap Make notes.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        return
    }
    if (isCompact()) {
        // Upright tablet: the boxes go under the note instead of beside it.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NoteMainColumn(note, topic.id, editing, onEditDone, vm)
            MustRememberBox(NoteLogic.mustRemember(note.sections))
            ProgressBox(d, vm)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NoteMainColumn(note, topic.id, editing, onEditDone, vm)
        }
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MustRememberBox(NoteLogic.mustRemember(note.sections))
            ProgressBox(d, vm)
        }
    }
}

/** The note itself: the editor while editing, otherwise the text with its suggestions. */
@Composable
private fun NoteMainColumn(note: Note, topicId: String, editing: Boolean, onEditDone: () -> Unit, vm: NotesViewModel) {
    if (editing) {
        NoteEditor(
            initial = note.contentMd,
            ownerEdited = note.ownerEdited,
            onSave = { text ->
                vm.saveText(note, text)
                onEditDone()
            },
            onCancel = onEditDone,
        )
    } else {
        NoteText(note, topicId, vm)
    }
}

@Composable
private fun NoteText(note: Note, topicId: String, vm: NotesViewModel) {
    val parts = NoteLogic.split(note.contentMd)
    if (NoteLogic.isBlankNote(parts.body) && parts.suggestions.isEmpty()) {
        val text = when (note.status) {
            "generating" -> "Making your notes from the material in your Library..."
            "failed" -> "The notes could not be made this time. Tap Make notes to try again."
            else -> NoteLogic.NO_MATERIAL_TEXT
        }
        CcCard(Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
            BigButton("Make notes", onClick = { vm.makeNotes(topicId) })
        }
        return
    }
    if (parts.body.isNotBlank()) {
        Text(
            if (note.ownerEdited) "Your notes" else "Made from your material by the AI. Check the sources.",
            style = MaterialTheme.typography.labelMedium, color = Cc.colors.muted,
        )
        MarkdownText(parts.body)
    }
    if (parts.suggestions.isNotEmpty()) SuggestionsPanel(note, parts.suggestions, vm)
    val sources = NoteLogic.sources(note.sources)
    if (sources.isNotEmpty()) {
        Text(
            "Sources: " + sources.take(4).joinToString("; ") { NoteLogic.sourceLine(it) } + if (sources.size > 4) " and more" else "",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
    }
}

/** New points found in new material. They are offered, never forced into the owner's text. */
@Composable
private fun SuggestionsPanel(note: Note, items: List<String>, vm: NotesViewModel) {
    val colors = Cc.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.accentTint).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("New from your material (${items.size})", style = MaterialTheme.typography.titleMedium, color = colors.onAccentTint)
        Text("Add the ones you want to your notes. Your own words are never changed.", style = MaterialTheme.typography.bodySmall, color = colors.onAccentTint)
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item, style = MaterialTheme.typography.bodyMedium, color = colors.ink, modifier = Modifier.weight(1f))
                BigButton("Add", onClick = { vm.saveText(note, NoteLogic.accept(note.contentMd, item)) })
                Text("  ")
                BigButton("Skip", onClick = { vm.saveText(note, NoteLogic.dismiss(note.contentMd, item)) }, filled = false)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Add all", onClick = { vm.saveText(note, NoteLogic.acceptAll(note.contentMd)) })
            BigButton("Skip all", onClick = { vm.saveText(note, NoteLogic.dismissAll(note.contentMd)) }, filled = false)
        }
    }
}

@Composable
private fun MustRememberBox(items: List<String>) {
    val colors = Cc.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.accentTint).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Must remember", style = MaterialTheme.typography.titleMedium, color = colors.onAccentTint)
        if (items.isEmpty()) {
            Text("Nothing yet. The key facts appear here once notes are made.", style = MaterialTheme.typography.bodySmall, color = colors.onAccentTint)
        }
        items.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium, color = colors.ink) }
    }
}

/** Status, next revision, card count and weak cards. */
@Composable
private fun ProgressBox(d: DetailState, vm: NotesViewModel) {
    val topic = d.topic ?: return
    var menu by remember { mutableStateOf(false) }
    val due = d.cards.mapNotNull { com.naveen.civilscompanion.data.records.TimeUtil.parse(it.dueAt) }
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Your progress")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Status", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(TopicTree.statusLabel(topic.status)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    TopicTree.STATUSES.forEach { st ->
                        DropdownMenuItem(
                            text = { Text(TopicTree.statusLabel(st)) },
                            onClick = { menu = false; vm.setStatus(topic.id, st) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        StatLine("Next revision", NoteLogic.nextRevision(due, System.currentTimeMillis()))
        StatLine("Flashcards", "${d.cards.size}")
        StatLine("Weak cards", "${d.weakCards}")
        StatLine("Questions", "${d.mcqs.size}")
        val note = d.note
        if (note != null) {
            StatLine("Version", "${note.version}")
            OutlinedButton(onClick = { vm.openVersions(note.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Earlier versions") }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
    }
}

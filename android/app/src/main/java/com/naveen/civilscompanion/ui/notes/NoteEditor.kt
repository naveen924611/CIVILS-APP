package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.MarkdownText

/**
 * Editing the note as Markdown text (spec 6.6 "Edit"). Saving marks the note as written by the owner, so the AI never
 * overwrites it: new material is offered under "Suggested additions" instead.
 */
@Composable
fun NoteEditor(initial: String, ownerEdited: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var preview by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton("Save", onClick = { onSave(text) })
            BigButton("Cancel", onClick = onCancel, filled = false)
            BigButton(if (preview) "Back to typing" else "Preview", onClick = { preview = !preview }, filled = false)
            Text("${NoteLogic.wordCount(text)} words", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        if (!ownerEdited) {
            Text(
                "Once you save, this note counts as yours. New material from the AI will be offered as suggestions and will never replace your words.",
                style = MaterialTheme.typography.bodySmall,
                color = Cc.colors.muted,
            )
        }
        if (preview) {
            CcCard(Modifier.fillMaxWidth()) { MarkdownText(text.ifBlank { "Nothing written yet." }) }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Write with # headings, - lists and **bold**") },
                minLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

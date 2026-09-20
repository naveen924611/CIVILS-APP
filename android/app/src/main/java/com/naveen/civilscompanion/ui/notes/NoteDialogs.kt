package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc

/** A box with one text field: used for "Report error" and "Add to notes". */
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    label: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(label) },
                    minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()); onDismiss() }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** Earlier versions of a note: view one, or bring it back (the version now is kept too). */
@Composable
fun VersionsDialog(
    ui: VersionsUi,
    onClose: () -> Unit,
    onView: (Int) -> Unit,
    onRestore: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Earlier versions") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val preview = ui.preview
                when {
                    ui.loading -> Text("Looking...", color = Cc.colors.muted)
                    preview != null -> {
                        Text("Version ${preview.version}, ${NoteLogic.shortDate(preview.createdAt)}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                        Text(preview.contentMd.take(4000), style = MaterialTheme.typography.bodySmall, color = Cc.colors.ink)
                    }
                    ui.versions.isEmpty() -> Text(
                        ui.error ?: "There are no earlier versions yet. A version is kept every time your note changes.",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                    )
                    else -> {
                        Text("Now: version ${ui.current}", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                        ui.versions.forEach { v ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Version ${v.version}  |  ${NoteLogic.shortDate(v.createdAt)}  |  ${v.chars} letters",
                                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.ink, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onView(v.version) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View") }
                            }
                        }
                    }
                }
                if (ui.error != null && (ui.preview != null || ui.versions.isNotEmpty())) {
                    Text(ui.error, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onDangerTint)
                }
            }
        },
        confirmButton = {
            val preview = ui.preview
            if (preview != null) {
                TextButton(onClick = { onRestore(preview.version) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bring this version back") }
            } else {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        },
        dismissButton = {
            if (ui.preview != null) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        },
    )
}

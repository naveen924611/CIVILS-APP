package com.naveen.civilscompanion.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.reader.ReadingPosition
import com.naveen.civilscompanion.reader.readingPercent
import com.naveen.civilscompanion.reader.statusLook
import com.naveen.civilscompanion.reader.typeLabel
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** One document in "My uploads": name, status, reading progress, and buttons. */
@Composable
internal fun DocumentCard(doc: LibDocument, subjects: List<Topic>, vm: LibraryViewModel, onOpen: () -> Unit) {
    val look = statusLook(doc.processingStatus, doc.statusDetail)
    val position = ReadingPosition.from(doc.readingPosition)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val canRetry = doc.processingStatus == "failed" || doc.processingStatus == "needs_ocr"

    CcCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(doc.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(typeLabel(doc.type))
                    Pill(look.label, tone = look.tone)
                    if (doc.pages > 0) Text("${doc.pages} pages", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    if (position.page > 1 && doc.pages > 0) {
                        Pill("Read ${readingPercent(position.page, doc.pages)}%")
                    }
                }
            }
            if (canRetry) BigButton("Try again", onClick = { vm.retry(doc) }, filled = false)
            BigButton("Open", onClick = onOpen)
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    for (subject in subjects) {
                        DropdownMenuItem(
                            text = { Text("Put under ${subject.title}") },
                            onClick = { menu = false; vm.moveToSubject(doc, subject.id) },
                        )
                    }
                    if (doc.topicId != null) {
                        DropdownMenuItem(text = { Text("Remove from subject") }, onClick = { menu = false; vm.moveToSubject(doc, null) })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this document?") },
            text = { Text("\"${doc.title}\" and its text will be removed from this tablet and the server.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteDocument(doc) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(doc, name) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

package com.naveen.civilscompanion.ui.syllabus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.theme.Cc

/**
 * "Import a syllabus": paste the text of the official syllabus, or pick a document from the Library that has been read.
 * The server turns it into a topic tree; the owner checks it before it is used (spec 7.1).
 */
@Composable
fun ImportSyllabusDialog(
    loadDocuments: suspend () -> List<LibDocument>,
    onDismiss: () -> Unit,
    onStart: (exam: String, title: String, text: String, documentId: String?) -> Unit,
) {
    var exam by remember { mutableStateOf("UPSC CSE") }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var doc by remember { mutableStateOf<LibDocument?>(null) }
    var docs by remember { mutableStateOf(emptyList<LibDocument>()) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { docs = loadDocuments() }

    val ready = exam.trim().length >= 2 && title.trim().length >= 2 && (text.isNotBlank() || doc != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a syllabus") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Copy the syllabus text from the official PDF (psc.ap.gov.in or upsc.gov.in) and paste it here. " +
                        "Nothing is used until you check it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cc.colors.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exam = "UPSC CSE" }) { Text("UPSC CSE") }
                    OutlinedButton(onClick = { exam = "APPSC Group-I" }) { Text("APPSC Group-I") }
                }
                OutlinedTextField(value = exam, onValueChange = { exam = it.take(80) }, label = { Text("Exam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(200) }, label = { Text("Name, for example Prelims 2026") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Syllabus text") },
                    minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = docs.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(doc?.title?.let { "From Library: $it" } ?: "Or use a document from my Library")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { doc = null; menu = false })
                        docs.forEach { d ->
                            DropdownMenuItem(text = { Text(d.title.ifBlank { "Untitled" }) }, onClick = { doc = d; menu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(exam, title, text, doc?.id) }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Start reading")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

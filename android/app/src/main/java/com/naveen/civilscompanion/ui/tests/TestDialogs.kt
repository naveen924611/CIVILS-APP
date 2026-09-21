package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.theme.Cc

/** Choose a topic and the number of questions for a topic test. */
@Composable
fun TopicTestDialog(topics: List<Topic>, onDismiss: () -> Unit, onMake: (topicId: String, count: Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(10) }
    val shown = topics.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }.take(60)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cc.colors.muted) } },
        title = { Text("Topic test", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Questions are made from your own notes of the topic.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(10, 15, 20).forEach { n -> TChip("$n questions", selected = count == n, onClick = { count = n }) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search topics") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) Text("No topics found. Approve your syllabus first.", color = Cc.colors.muted)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(shown, key = { it.id }) { t ->
                        Text(
                            t.title, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onMake(t.id, count) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

/** Exam, year and paper of a full past paper test (or of a paper to read into the app). */
@Composable
fun PaperDialog(
    title: String,
    help: String,
    documents: List<LibDocument>?,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (documentId: String, exam: String, year: Int, paper: String) -> Unit,
) {
    var exam by remember { mutableStateOf("UPSC") }
    var year by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf("") }
    var docId by remember { mutableStateOf("") }
    val yearNumber = year.trim().toIntOrNull()
    val ok = yearNumber != null && yearNumber in 1990..2100 && (documents == null || docId.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cc.colors.muted) } },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(docId, exam, yearNumber ?: 0, paper) }) { Text(confirmText, color = if (ok) Cc.colors.primary else Cc.colors.muted) }
        },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(help, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                if (documents != null) {
                    if (documents.isEmpty()) Text("No processed documents yet. Add the paper in the Library first.", color = Cc.colors.muted)
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(documents, key = { it.id }) { d ->
                            Text(
                                (if (d.id == docId) "[x] " else "[ ] ") + d.title, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { docId = d.id }.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TChip("UPSC", selected = exam == "UPSC", onClick = { exam = "UPSC" })
                    TChip("APPSC", selected = exam == "APPSC", onClick = { exam = "APPSC" })
                    TChip("SI", selected = exam == "SI", onClick = { exam = "SI" })
                }
                OutlinedTextField(value = year, onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true, label = { Text("Year, for example 2023") })
                OutlinedTextField(value = paper, onValueChange = { paper = it }, singleLine = true, label = { Text("Paper (optional), for example GS 1") })
            }
        },
    )
}

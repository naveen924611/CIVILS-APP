package com.naveen.civilscompanion.ui.answers

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
import com.naveen.civilscompanion.theme.NotoSansTelugu
import com.naveen.civilscompanion.ui.ask.ActionText
import com.naveen.civilscompanion.ui.ask.ChipButton

/** Practice prompts for the descriptive papers: filter by exam and language, tap one to start writing. */
@Composable
fun PromptPickerDialog(prompts: List<WritingPrompt>, onDismiss: () -> Unit, onPick: (WritingPrompt) -> Unit) {
    var exam by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf("en") }
    val shown = WritingPrompts.filter(prompts, exam, language)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Practice prompt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Prompts for the English and Telugu papers of SI (Civil) and APPSC Group-I. They work offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Cc.colors.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChipButton("All", selected = exam == null, onClick = { exam = null })
                    ChipButton("SI", selected = exam == "SI", onClick = { exam = "SI" })
                    ChipButton("APPSC", selected = exam == "APPSC", onClick = { exam = "APPSC" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChipButton("English", selected = language == "en", onClick = { language = "en" })
                    ChipButton("Telugu", selected = language == "te", onClick = { language = "te" })
                }
                if (shown.isEmpty()) {
                    Text("No prompts for this choice.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.key }) { p ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(WritingPrompts.rowLabel(p), style = MaterialTheme.typography.labelMedium, color = Cc.colors.muted)
                            Text(
                                WritingPrompts.preview(p),
                                style = if (p.language == "te") MaterialTheme.typography.bodyMedium.copy(fontFamily = NotoSansTelugu) else MaterialTheme.typography.bodyMedium,
                                color = Cc.colors.ink,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { ActionText("Close", onClick = onDismiss) },
    )
}

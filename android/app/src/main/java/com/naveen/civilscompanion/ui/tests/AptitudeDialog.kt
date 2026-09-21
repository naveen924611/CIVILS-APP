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

/** The areas of the aptitude drill the server can make (SI Civil arithmetic and reasoning). id is sent to the server. */
object AptitudeAreas {
    /** id to label. The first entry (empty id) means "mixed". */
    val ALL: List<Pair<String, String>> = listOf(
        "" to "Mixed",
        "percentage" to "Percentage",
        "profit_loss" to "Profit and loss",
        "simple_interest" to "Simple interest",
        "compound_interest" to "Compound interest",
        "ratio_proportion" to "Ratio and proportion",
        "average" to "Average",
        "time_work" to "Time and work",
        "work_wages" to "Work and wages",
        "time_distance" to "Time and distance",
        "clocks_calendars" to "Clocks and calendars",
        "partnership" to "Partnership",
        "mensuration" to "Mensuration",
        "number_system" to "Number system",
        "series" to "Series",
        "coding_decoding" to "Coding-decoding",
        "blood_relations" to "Blood relations",
        "direction_sense" to "Direction sense",
        "ranking_order" to "Ranking and order",
        "odd_one_out" to "Odd one out",
        "analogy" to "Analogy",
        "syllogism" to "Syllogism",
        "statements_conclusions" to "Statements and conclusions",
    )

    val COUNTS: List<Int> = listOf(10, 20, 30)

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: "Mixed"
}

/** Choose the area and the number of questions for an aptitude drill. onMake gets area null for "Mixed". */
@Composable
fun AptitudeDialog(onDismiss: () -> Unit, onMake: (area: String?, count: Int) -> Unit) {
    var area by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(20) }
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Cc.colors.muted) } },
        confirmButton = {
            TextButton(onClick = { onMake(area.ifBlank { null }, count) }) { Text("Make drill", color = Cc.colors.primary) }
        },
        title = { Text("Aptitude drill", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Arithmetic and reasoning questions with exact answers. Made on the server, so you need the internet once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cc.colors.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AptitudeAreas.COUNTS.forEach { n -> TChip("$n questions", selected = count == n, onClick = { count = n }) }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(AptitudeAreas.ALL, key = { it.first }) { entry ->
                        Text(
                            (if (entry.first == area) "[x] " else "[ ] ") + entry.second,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cc.colors.ink,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { area = entry.first }.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

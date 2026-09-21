package com.naveen.civilscompanion.ui.syllabus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.SyllabusImport
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** Syllabus map (spec 6.20). */
@Composable
fun SyllabusScreen(nav: NavHostController, vm: SyllabusViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var showImport by remember { mutableStateOf(false) }

    val allIds = remember(s.topics) { s.topics.map { it.id }.toSet() }
    val rows = remember(s.tree, expanded, allIds) { TopicTree.rows(s.tree, allIds - expanded) }
    val selected = selectedId?.let { TopicTree.find(s.tree, it) }
    val compact = isCompact()

    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        // Upright tablet: the map and the topic's panel take turns.
        if (!(compact && selected != null)) Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(
                "Syllabus map",
                subtitle = if (s.tree.isEmpty()) "Approve a syllabus to see your topics here" else "${s.overall}% of your topics are studied",
                actions = {
                    BigButton("Notes", onClick = { nav.navigate(Routes.NOTES) }, filled = false)
                    BigButton("Import a syllabus", onClick = { showImport = true })
                },
            )
            ExamChips(s.exam, onPick = vm::setExam)
            s.message?.let { Notice(it, onDismiss = vm::dismissMessage) }
            ImportStrip(s.imports, s.importJobs, onReview = { nav.navigate(Routes.syllabusReview(it)) })
            if (rows.isEmpty()) {
                EmptyState(
                    "No topics yet",
                    "Check one of the syllabus outlines above, or import your own. Approved topics show up here with your progress.",
                )
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows, key = { it.node.topic.id }) { row ->
                        val id = row.node.topic.id
                        TopicRowView(
                            row = row,
                            expanded = id in expanded,
                            selected = id == selectedId,
                            onSelect = { selectedId = id },
                            onToggle = { expanded = if (id in expanded) expanded - id else expanded + id },
                        )
                    }
                }
            }
        }
        if (selected != null) {
            val topic = selected.topic
            val panel: @Composable (Modifier) -> Unit = { panelModifier ->
                TopicDetailPanel(
                    node = selected,
                    path = TopicTree.path(s.topics, topic.id),
                    onStatus = { vm.setStatus(topic.id, it) },
                    onOpenNotes = { nav.navigate(Routes.noteTopic(topic.id)) },
                    onAsk = {
                        vm.askAbout(topic)
                        nav.navigate(Routes.ASK)
                    },
                    modifier = panelModifier,
                )
            }
            if (compact) {
                Column(Modifier.weight(1f).fillMaxHeight().background(Cc.colors.surface)) {
                    BigButton(
                        "Back to the map", onClick = { selectedId = null }, filled = false,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    )
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                panel(Modifier.width(340.dp).fillMaxHeight().background(Cc.colors.surface))
            }
        }
    }
    if (showImport) {
        ImportSyllabusDialog(
            loadDocuments = vm::documents,
            onDismiss = { showImport = false },
            onStart = { exam, title, text, documentId ->
                showImport = false
                vm.startImport(exam, title, text, documentId)
            },
        )
    }
}

@Composable
private fun ExamChips(current: String?, onPick: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf<Pair<String?, String>>(null to "All exams", "APPSC" to "APPSC", "UPSC" to "UPSC", "SI" to "SI").forEach { (value, label) ->
            val chosen = value == current
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (chosen) Cc.colors.onPrimary else Cc.colors.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) Cc.colors.primary else Cc.colors.surface)
                    .border(1.dp, Cc.colors.border, RoundedCornerShape(50))
                    .clickable { onPick(value) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Cc.colors.accentTint).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Cc.colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
    }
}

/** Syllabus files waiting to be checked (or still being read) above the tree. */
@Composable
private fun ImportStrip(imports: List<SyllabusImport>, jobs: List<Job>, onReview: (String) -> Unit) {
    if (imports.isEmpty() && jobs.isEmpty()) return
    CcCard(Modifier.fillMaxWidth()) {
        Text("Syllabus outlines", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        jobs.forEach { job ->
            val title = (job.payload["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val line = if (job.status == "failed") "Could not read \"$title\": ${job.error.ifBlank { "please try again" }}"
            else "Waiting to read \"$title\" (needs internet)"
            Text(line, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        imports.forEach { imp ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(imp.title, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    if (imp.note.isNotBlank()) Text(imp.note, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
                when (imp.status) {
                    "pending" -> BigButton("Check and approve", onClick = { onReview(imp.id) })
                    "failed" -> Pill("Could not read", tone = 3)
                    else -> Pill("Reading...", tone = 2)
                }
            }
        }
    }
}

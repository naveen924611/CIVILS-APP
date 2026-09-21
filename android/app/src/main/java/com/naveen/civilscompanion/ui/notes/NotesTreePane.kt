package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.syllabus.StatusDot
import com.naveen.civilscompanion.ui.syllabus.TopicTree

/** Left side of the Notes screen (spec 6.6): search, exam filter, and the subject -> topic tree. */
@Composable
fun NotesTreePane(s: NotesListState, vm: NotesViewModel, onOpenSyllabus: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(s.selectedId, s.topics.size) {
        val id = s.selectedId
        if (id != null) expanded = expanded + TopicTree.ancestors(s.topics, id)
    }
    val allIds = remember(s.topics) { s.topics.map { it.id }.toSet() }
    val rows = remember(s.tree, expanded, allIds) { TopicTree.rows(s.tree, allIds - expanded) }
    val searching = s.query.trim().length >= 2

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search topics and notes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Pair<String?, String>>(null to "All", "APPSC" to "APPSC", "UPSC" to "UPSC", "SI" to "SI").forEach { (value, label) ->
                val chosen = value == s.exam
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) Cc.colors.onPrimary else Cc.colors.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (chosen) Cc.colors.primary else Cc.colors.surface)
                        .border(1.dp, Cc.colors.border, RoundedCornerShape(50))
                        .clickable { vm.setExam(value) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        if (searching) {
            if (s.hits.isEmpty()) Text("Nothing found for \"${s.query.trim()}\".", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.hits, key = { it.topic.id }) { hit ->
                    TreeLine(
                        title = hit.topic.title, status = hit.topic.status, depth = 0, selected = hit.topic.id == s.selectedId,
                        arrow = null, subtitle = hit.snippet, onClick = { vm.select(hit.topic.id) }, onArrow = {},
                    )
                }
            }
        } else if (rows.isEmpty()) {
            Text(
                "No topics yet. Approve a syllabus in the Syllabus map, then your subjects and topics appear here.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.node.topic.id }) { row ->
                    val id = row.node.topic.id
                    val hasKids = row.node.children.isNotEmpty()
                    TreeLine(
                        title = row.node.topic.title, status = row.node.topic.status, depth = row.depth, selected = id == s.selectedId,
                        arrow = if (!hasKids) null else if (id in expanded) "v" else ">", subtitle = "",
                        onClick = { vm.select(id) },
                        onArrow = { expanded = if (id in expanded) expanded - id else expanded + id },
                    )
                }
            }
        }
        BigButton("Syllabus map", onClick = onOpenSyllabus, filled = false, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TreeLine(
    title: String,
    status: String,
    depth: Int,
    selected: Boolean,
    arrow: String?,
    subtitle: String,
    onClick: () -> Unit,
    onArrow: () -> Unit,
) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clip(shape)
            .background(if (selected) colors.primaryTint else colors.background)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clickable(enabled = arrow != null, onClick = onArrow), contentAlignment = Alignment.Center) {
            if (arrow != null) Text(arrow, style = MaterialTheme.typography.titleMedium, color = colors.primary)
        }
        StatusDot(status)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryTint else colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

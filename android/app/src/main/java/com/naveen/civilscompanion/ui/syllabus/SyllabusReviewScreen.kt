package com.naveen.civilscompanion.ui.syllabus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** Import review (spec 6.20 "approval mode"): check the topics the server found, fix them, then approve. */
@Composable
fun SyllabusReviewScreen(nav: NavHostController, importId: String, vm: SyllabusReviewViewModel = hiltViewModel()) {
    LaunchedEffect(importId) { vm.load(importId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val imp = s.imp
    fun leave() {
        if (!nav.popBackStack()) nav.navigate(Routes.SYLLABUS)
    }

    Column(Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = if (isCompact()) 16.dp else 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(
            imp?.title ?: "Check a syllabus",
            subtitle = imp?.let { "${it.exam}  |  ${SyllabusTree.count(s.tree)} lines" },
            actions = { BigButton("Back", onClick = { leave() }, filled = false) },
        )
        val result = s.result
        val status = imp?.status.orEmpty()
        val note = imp?.note.orEmpty()
        when {
            s.loading -> CircularProgressIndicator(color = Cc.colors.primary)
            imp == null -> EmptyState("Not found", "This syllabus is not on the tablet yet. Connect to the internet and open it again.")
            result != null -> DoneCard(result, onBack = { leave() })
            status == "processing" -> EmptyState("Reading your syllabus", note.ifBlank { "The server is reading it. This can take a minute." })
            status == "failed" && s.tree.isEmpty() -> EmptyState("Could not read it", note.ifBlank { "Please try the import again." })
            status == "approved" -> EmptyState("Already approved", "The topics of this syllabus are in your syllabus map.")
            else -> ReviewBody(s, vm)
        }
    }
}

@Composable
private fun DoneCard(result: ApproveResult, onBack: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("Approved", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        Text(
            "${result.created} new topics were added and ${result.merged} were already in your syllabus. " +
                "They appear in the syllabus map in a moment (each one also gets an empty note).",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.muted,
        )
        BigButton("Open the syllabus map", onClick = onBack)
    }
}

@Composable
private fun ColumnScope.ReviewBody(s: ReviewState, vm: SyllabusReviewViewModel) {
    val imp = s.imp ?: return
    var action by remember { mutableStateOf<ReviewAction?>(null) }
    val rows = remember(s.tree, s.collapsed) { SyllabusTree.rows(s.tree, s.collapsed) }
    val actions = remember(vm) {
        ReviewActions(
            onToggle = vm::toggle,
            onAsk = { action = it },
            onMoveUp = vm::moveUp,
            onMoveDown = vm::moveDown,
            onMerge = vm::mergeUp,
            onTags = vm::setTags,
        )
    }
    if (imp.note.isNotBlank()) {
        Text(imp.note, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
    s.message?.let { msg ->
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Cc.colors.accentTint).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(msg, color = Cc.colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
        }
    }
    Text(
        "Tap a line to rename it, or use the ... menu to add, split, merge, move or delete. Nothing is used until you approve.",
        style = MaterialTheme.typography.bodySmall,
        color = Cc.colors.muted,
    )
    if (rows.isEmpty()) {
        EmptyState("Nothing to check", "This syllabus has no lines. Go back and import it again.")
    } else {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.node.key }) { row ->
                ReviewRowView(row, s.tree, collapsed = row.node.key in s.collapsed, actions = actions)
            }
            item {
                BigButton("Add a top-level line", onClick = { action = ReviewAction("add", null) }, filled = false)
            }
        }
    }
    BottomBar(s, vm)
    action?.let { current ->
        ReviewActionDialog(
            action = current,
            current = current.key?.let { SyllabusTree.find(s.tree, it) },
            onDismiss = { action = null },
            onRename = vm::rename,
            onAdd = vm::addChild,
            onSplit = vm::split,
            onDelete = vm::remove,
        )
    }
}

@Composable
private fun BottomBar(s: ReviewState, vm: SyllabusReviewViewModel) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(12.dp)
    if (isCompact()) {
        // Upright tablet: three short rows instead of one crowded row.
        Column(
            Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.border, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use:", style = MaterialTheme.typography.labelLarge, color = colors.muted)
                listOf<Pair<String?, String>>(null to "All exams", "APPSC" to "APPSC only", "UPSC" to "UPSC only", "SI" to "SI only").forEach { (value, label) ->
                    val chosen = value == s.examFilter
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chosen) colors.onPrimary else colors.ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (chosen) colors.primary else colors.rail)
                            .clickable { vm.setExamFilter(value) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.mergeWithExisting, onCheckedChange = vm::setMerge)
                Text("Join topics I already have", style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Save for later", onClick = vm::saveDraft, filled = false, enabled = !s.busy && s.edited, modifier = Modifier.weight(1f))
                BigButton(if (s.busy) "Working..." else "Approve ${s.approveCount} topics", onClick = vm::approve, enabled = !s.busy, modifier = Modifier.weight(1f))
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.border, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Use:", style = MaterialTheme.typography.labelLarge, color = colors.muted)
        listOf<Pair<String?, String>>(null to "All exams", "APPSC" to "APPSC only", "UPSC" to "UPSC only", "SI" to "SI only").forEach { (value, label) ->
            val chosen = value == s.examFilter
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (chosen) colors.onPrimary else colors.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) colors.primary else colors.rail)
                    .clickable { vm.setExamFilter(value) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Switch(checked = s.mergeWithExisting, onCheckedChange = vm::setMerge)
        Text("Join topics I already have", style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
        BigButton("Save for later", onClick = vm::saveDraft, filled = false, enabled = !s.busy && s.edited)
        BigButton(if (s.busy) "Working..." else "Approve ${s.approveCount} topics", onClick = vm::approve, enabled = !s.busy)
    }
}

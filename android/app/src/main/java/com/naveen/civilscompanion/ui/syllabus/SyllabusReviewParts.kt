package com.naveen.civilscompanion.ui.syllabus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.Pill

/** What the owner is doing to one line: `kind` is "rename", "add", "split" or "delete"; `key` = the line (null = top level for "add"). */
data class ReviewAction(val kind: String, val key: Int?)

/** Everything a line can do, given to the row as small lambdas. */
class ReviewActions(
    val onToggle: (Int) -> Unit,
    val onAsk: (ReviewAction) -> Unit,
    val onMoveUp: (Int) -> Unit,
    val onMoveDown: (Int) -> Unit,
    val onMerge: (Int) -> Unit,
    val onTags: (Int, List<String>) -> Unit,
)

@Composable
fun ReviewRowView(row: SRow, tree: List<SNode>, collapsed: Boolean, actions: ReviewActions) {
    val colors = Cc.colors
    val node = row.node
    val shape = RoundedCornerShape(10.dp)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 22).dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clickable(enabled = node.children.isNotEmpty()) { actions.onToggle(node.key) },
            contentAlignment = Alignment.Center,
        ) {
            if (node.children.isNotEmpty()) {
                Text(if (collapsed) ">" else "v", style = MaterialTheme.typography.titleMedium, color = colors.primary)
            }
        }
        Column(Modifier.weight(1f).clickable { actions.onAsk(ReviewAction("rename", node.key)) }.padding(vertical = 6.dp)) {
            Text(
                node.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (node.examTags.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                node.examTags.forEach { Pill(it) }
            }
        }
        Box {
            Box(Modifier.size(48.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Text("...", style = MaterialTheme.typography.titleMedium, color = colors.muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Rename") { menu = false; actions.onAsk(ReviewAction("rename", node.key)) }
                MenuItem("Add a sub-topic") { menu = false; actions.onAsk(ReviewAction("add", node.key)) }
                MenuItem("Split into several topics") { menu = false; actions.onAsk(ReviewAction("split", node.key)) }
                if (SyllabusTree.canMoveUp(tree, node.key)) {
                    MenuItem("Merge into the topic above") { menu = false; actions.onMerge(node.key) }
                    MenuItem("Move up") { menu = false; actions.onMoveUp(node.key) }
                }
                if (SyllabusTree.canMoveDown(tree, node.key)) MenuItem("Move down") { menu = false; actions.onMoveDown(node.key) }
                MenuItem("Exam: UPSC only") { menu = false; actions.onTags(node.key, listOf("UPSC")) }
                MenuItem("Exam: APPSC only") { menu = false; actions.onTags(node.key, listOf("APPSC")) }
                MenuItem("Exam: both") { menu = false; actions.onTags(node.key, listOf("APPSC", "UPSC")) }
                MenuItem("Delete") { menu = false; actions.onAsk(ReviewAction("delete", node.key)) }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp))
}

/** The small question box for rename / add / split / delete. */
@Composable
fun ReviewActionDialog(
    action: ReviewAction,
    current: SNode?,
    onDismiss: () -> Unit,
    onRename: (Int, String) -> Unit,
    onAdd: (Int?, String) -> Unit,
    onSplit: (Int, List<String>) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val key = action.key
    var text by remember { mutableStateOf(if (action.kind == "rename") current?.title.orEmpty() else "") }
    if (action.kind == "delete") {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete this topic?") },
            text = { Text("\"${current?.title.orEmpty()}\" and everything inside it will be removed from this list. Nothing else is changed.") },
            confirmButton = {
                TextButton(onClick = { if (key != null) onDelete(key); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep it") } },
        )
        return
    }
    val (title, label, hint) = when (action.kind) {
        "add" -> Triple("Add a sub-topic", "Name", "It is added at the end of \"${current?.title ?: "the list"}\".")
        "split" -> Triple("Split into several topics", "Names, separated by ; or new lines", "The first name keeps the sub-topics that are inside now.")
        else -> Triple("Rename", "Name", "Use the official words of the syllabus.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(label) },
                    singleLine = action.kind != "split", minLines = if (action.kind == "split") 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (action.kind) {
                        "add" -> onAdd(key, text)
                        "split" -> if (key != null) onSplit(key, SyllabusTree.splitTitles(text))
                        else -> if (key != null) onRename(key, text)
                    }
                    onDismiss()
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

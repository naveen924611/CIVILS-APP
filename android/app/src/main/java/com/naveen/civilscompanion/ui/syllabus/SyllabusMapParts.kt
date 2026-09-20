package com.naveen.civilscompanion.ui.syllabus

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel

/** Colour of a status (spec 6.20: not started, in progress, studied, revised, strong). Built from the theme colours only. */
@Composable
fun statusColor(status: String): Color {
    val c = Cc.colors
    return when (status) {
        "in_progress" -> c.onAccentTint
        "studied" -> c.primary.copy(alpha = 0.55f)
        "revised" -> c.primary
        "strong" -> c.onPrimaryTint
        else -> c.border
    }
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(statusColor(status)).border(1.dp, Cc.colors.border, CircleShape))
}

/** One line of the map: expand arrow, status dot, title, importance marker and coverage. Tap = select. */
@Composable
fun TopicRowView(
    row: TRow,
    expanded: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = Cc.colors
    val node = row.node
    val topic = node.topic
    val hasKids = node.children.isNotEmpty()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 22).dp)
            .clip(shape)
            .background(if (selected) colors.primaryTint else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.border, shape)
            .clickable(onClick = onSelect)
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clickable(enabled = hasKids, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (hasKids) Text(if (expanded) "v" else ">", style = MaterialTheme.typography.titleMedium, color = colors.primary)
        }
        StatusDot(topic.status)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                topic.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasKids && row.depth <= 1) {
                LinearProgressIndicator(
                    progress = { node.coverage / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primary,
                    trackColor = colors.rail,
                )
            }
        }
        if (topic.importance >= 7.0) Pill("High", tone = 2)
        Text(
            if (hasKids) "${node.coverage}%" else TopicTree.statusLabel(topic.status),
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

/** The side panel for the selected topic: where it sits, importance, coverage, status choice, open notes. */
@Composable
fun TopicDetailPanel(
    node: TNode,
    path: String,
    onStatus: (String) -> Unit,
    onOpenNotes: () -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Cc.colors
    val topic = node.topic
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(topic.title, style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text(path, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            topic.examTags.forEach { Pill(it, tone = 1) }
            Pill("Importance: ${TopicTree.importanceLabel(topic.importance)}", tone = if (topic.importance >= 7.0) 2 else 0)
        }
        Text(
            "About ${formatHours(topic.estHours)} of study. " +
                if (node.children.isNotEmpty()) "${node.covered} of ${node.leaves} topics inside are studied." else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        BigButton("Open notes", onClick = onOpenNotes, modifier = Modifier.fillMaxWidth())
        BigButton("Ask about this", onClick = onAsk, modifier = Modifier.fillMaxWidth(), filled = false)
        SectionLabel("How far are you?")
        TopicTree.STATUSES.forEach { status ->
            val chosen = status == topic.status
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (chosen) colors.primaryTint else colors.surface)
                    .border(1.dp, if (chosen) colors.primary else colors.border, RoundedCornerShape(10.dp))
                    .clickable { onStatus(status) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(status)
                Text(TopicTree.statusLabel(status), style = MaterialTheme.typography.bodyMedium, color = colors.ink, modifier = Modifier.weight(1f))
                if (chosen) Text("Chosen", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryTint)
            }
        }
        if (node.children.isNotEmpty()) {
            Text(
                "Tip: the colour of a group follows the topics inside it. Change the status of the small topics.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

private fun formatHours(hours: Double): String =
    if (hours >= 10 || hours == Math.floor(hours)) "${hours.toInt()} h" else "%.1f h".format(hours)

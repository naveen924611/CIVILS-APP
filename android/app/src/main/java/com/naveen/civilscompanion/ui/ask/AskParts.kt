package com.naveen.civilscompanion.ui.ask

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.MarkdownText
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.voice.VoiceGrammar

/** A round-cornered choice button (48 dp tall). selected = filled with the main colour. */
@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cc.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary else c.rail)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onPrimary else c.ink)
    }
}

/** A text-only button (48 dp tall) for small actions under a card. */
@Composable
fun ActionText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (danger) Cc.colors.onDangerTint else Cc.colors.primary,
        )
    }
}

@Composable
fun ChatBubble(
    bubble: Bubble,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (bubble.fromUser) UserBubble(bubble) else AnswerBubble(bubble, speaking, onSpeak, onSave, onMore, onOpenSource)
    }
}

@Composable
private fun UserBubble(b: Bubble) {
    val c = Cc.colors
    Column(
        modifier = Modifier
            .widthIn(max = 620.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.primaryTint)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(b.text, style = MaterialTheme.typography.bodyLarge, color = c.onPrimaryTint)
        val how = if (b.via == "voice") "Voice" else "Typed"
        Text(
            listOf(b.time, how).filter { it.isNotEmpty() }.joinToString(" - "),
            style = MaterialTheme.typography.labelSmall, color = c.onPrimaryTint,
        )
        if (b.status.isNotEmpty()) {
            Text(
                b.status, style = MaterialTheme.typography.labelMedium,
                color = if (b.failed) c.onDangerTint else c.muted,
            )
        }
    }
}

@Composable
private fun AnswerBubble(
    b: Bubble,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    CcCard(modifier = Modifier.widthIn(max = 780.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (b.offline) Pill("Answered offline from your notes", tone = 2)
            if (b.topicTitle.isNotEmpty()) Pill(b.topicTitle)
            if (b.time.isNotEmpty()) Text(b.time, style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
        }
        MarkdownText(AskLogic.forDisplay(b.text))
        if (b.sources.isNotEmpty()) {
            SectionLabel("Sources")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.sources.forEach { s ->
                    val route = s.route
                    if (route != null) {
                        ActionText(s.label, onClick = { onOpenSource(route) })
                    } else {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionText(if (speaking) "Stop reading" else "Listen", onClick = onSpeak)
            ActionText("Save to notes", onClick = onSave)
            if (b.offline) ActionText("Ask the tutor for more", onClick = onMore)
        }
    }
}

/** Right-hand panel: the questions waiting for the server, and what can be said offline. */
@Composable
fun WaitingPanel(rows: List<WaitingRow>, count: Int, modifier: Modifier = Modifier) {
    val c = Cc.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(modifier = Modifier.fillMaxWidth()) {
            Text("Waiting for internet · $count", style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (rows.isEmpty()) {
                Text("Nothing is waiting. Questions asked without internet appear here.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            rows.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(r.question, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(r.status, tone = r.tone)
                        Text(listOf(r.time, r.how).filter { it.isNotEmpty() }.joinToString(" - "), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                }
            }
        }
        CcCard(modifier = Modifier.fillMaxWidth()) {
            Text("You can say, even offline", style = MaterialTheme.typography.titleMedium, color = c.ink)
            VoiceGrammar.EXAMPLES.forEach { phrase ->
                Text(
                    "“$phrase”", style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.rail)
                        .border(1.dp, c.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

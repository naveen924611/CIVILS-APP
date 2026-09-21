package com.naveen.civilscompanion.ui.revise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.srs.RevisionQueue
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.common.rememberMicPermission

/** Revise: card session (spec 6.5). Card k of n, question, answer, Again / Hard / Good / Easy with the next interval. */
@Composable
fun ReviseSessionScreen(nav: NavHostController, vm: ReviseSessionViewModel = hiltViewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    var micMessage by remember { mutableStateOf<String?>(null) }
    val askMic = rememberMicPermission(
        onDenied = { micMessage = "The microphone is off, so hands-free cannot listen. You can allow it in the tablet settings." },
    ) { vm.setHandsFree(true) }

    val compact = isCompact()
    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background)
            .padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            s.loading -> Text("Getting your cards ready...", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted)
            s.total == 0 -> {
                EmptyState("Nothing to revise", "No cards are due right now. Come back later, or make notes and cards from the Notes screen.")
            }
            s.finished -> FinishedPanel(s, onUndo = vm::undo, onBack = { nav.popBackStack() })
            else -> {
                SessionHeader(s, onHandsFree = { on -> if (on) askMic() else vm.setHandsFree(false) })
                val card = s.card
                if (card != null) {
                    QuestionCard(s, vm, Modifier.weight(1f).fillMaxWidth())
                }
                micMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onDangerTint) }
                if (s.status.isNotBlank()) Text(s.status, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                Controls(s, vm)
            }
        }
    }
}

@Composable
private fun SessionHeader(s: SessionUi, onHandsFree: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Card ${s.index + 1} of ${s.total}", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
                if (s.subjectMix.isNotBlank()) Text(s.subjectMix, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
            Text("Hands-free", style = MaterialTheme.typography.labelLarge, color = Cc.colors.ink, modifier = Modifier.padding(end = 8.dp))
            Switch(checked = s.handsFree, onCheckedChange = onHandsFree)
        }
        LinearProgressIndicator(
            progress = { if (s.total == 0) 0f else (s.index.toFloat() / s.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = Cc.colors.primary,
            trackColor = Cc.colors.border,
        )
    }
}

private fun reasonTone(reason: String): Int = when (reason) {
    RevisionQueue.REASON_WEAK -> 3
    RevisionQueue.REASON_FADING, RevisionQueue.REASON_PAPERS -> 2
    RevisionQueue.REASON_PINNED, RevisionQueue.REASON_SUNDAY -> 1
    else -> 0
}

@Composable
private fun QuestionCard(s: SessionUi, vm: ReviseSessionViewModel, modifier: Modifier) {
    val card = s.card ?: return
    CcCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.topicTitle.isNotBlank()) Pill(s.topicTitle)
            if (s.reason.isNotBlank()) Pill(s.reason, tone = reasonTone(s.reason))
            if (s.pastPapers) Pill("Asked in past papers", tone = 2)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(card.front, style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
            if (s.revealed) {
                HorizontalDivider(color = Cc.colors.border)
                Text(card.back, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::listen, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Text(if (s.revealed) "Listen to answer" else "Listen to question", color = Cc.colors.primary)
            }
            TextButton(onClick = vm::stopSpeaking, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop voice") }
            if (s.canUndo) TextButton(onClick = vm::undo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo last") }
        }
    }
}

@Composable
private fun Controls(s: SessionUi, vm: ReviseSessionViewModel) {
    if (!s.revealed) {
        BigButton("Show answer", onClick = vm::reveal, modifier = Modifier.fillMaxWidth())
        return
    }
    val c = Cc.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        GradeButton("Again", s.intervals[1].orEmpty(), c.dangerTint, c.onDangerTint, Modifier.weight(1f)) { vm.grade(1) }
        GradeButton("Hard", s.intervals[2].orEmpty(), c.accentTint, c.onAccentTint, Modifier.weight(1f)) { vm.grade(2) }
        GradeButton("Good", s.intervals[3].orEmpty(), c.primaryTint, c.onPrimaryTint, Modifier.weight(1f)) { vm.grade(3) }
        GradeButton("Easy", s.intervals[4].orEmpty(), c.primary, c.onPrimary, Modifier.weight(1f)) { vm.grade(4) }
    }
}

@Composable
private fun GradeButton(label: String, interval: String, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (interval.isNotEmpty()) Text(interval, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FinishedPanel(s: SessionUi, onUndo: () -> Unit, onBack: () -> Unit) {
    val done = s.counts.sum()
    CcCard(Modifier.fillMaxWidth()) {
        Text("Session finished", style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
        Text(
            if (done == 1) "You revised 1 card. Well done." else "You revised $done cards. Well done.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cc.colors.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Again ${s.counts[0]}", tone = 3)
            Pill("Hard ${s.counts[1]}", tone = 2)
            Pill("Good ${s.counts[2]}", tone = 1)
            Pill("Easy ${s.counts[3]}", tone = 1)
        }
        if (s.counts[0] > 0) {
            Text("Cards you marked Again come back tomorrow.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Back to Revise", onClick = onBack)
            if (s.canUndo) BigButton("Undo last card", onClick = onUndo, filled = false)
        }
    }
}

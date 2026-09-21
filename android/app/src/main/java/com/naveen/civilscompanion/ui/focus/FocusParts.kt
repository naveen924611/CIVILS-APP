package com.naveen.civilscompanion.ui.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.data.model.FocusSession
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.today.PlanBlock

/** Large circular countdown with the time in the middle. */
@Composable
fun TimerCircle(progress: Float, clock: String, label: String) {
    val track = Cc.colors.border
    val arc = Cc.colors.primary
    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = box, style = Stroke(width = stroke),
            )
            drawArc(
                color = arc, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = Offset(inset, inset), size = box, style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(clock, style = MaterialTheme.typography.displayMedium, color = Cc.colors.ink)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
    }
}

/** "How much did you finish?" with the four answers. */
@Composable
fun AskProgress(onAnswer: (Int) -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("Session done. How much did you finish?", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(
            "Your answer helps the planner see how fast you work.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusLogic.PROGRESS_CHOICES.forEach { pct ->
                BigButton("$pct%", onClick = { onAnswer(pct) }, filled = pct == 100)
            }
        }
    }
}

/** Choosing a style (50+10, 25+5, custom) and, for custom, the two lengths. */
@Composable
fun StylePicker(
    style: String,
    onStyle: (String) -> Unit,
    customFocus: Int,
    customBreak: Int,
    onCustom: (Int, Int) -> Unit,
) {
    SectionLabel("Session style")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(FocusLogic.STYLE_50 to "50 + 10", FocusLogic.STYLE_25 to "25 + 5", FocusLogic.STYLE_CUSTOM to "Custom").forEach { (id, label) ->
            FilterChip(
                selected = style == id, onClick = { onStyle(id) }, label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    if (style == FocusLogic.STYLE_CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Work", color = Cc.colors.muted)
            Stepper("$customFocus min", onMinus = { onCustom(customFocus - 5, customBreak) }, onPlus = { onCustom(customFocus + 5, customBreak) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Break", color = Cc.colors.muted)
            Stepper("$customBreak min", onMinus = { onCustom(customFocus, customBreak - 5) }, onPlus = { onCustom(customFocus, customBreak + 5) })
        }
    }
}

@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text("−5") }
        Text(text, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        OutlinedButton(onClick = onPlus, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text("+5") }
    }
}

/** Chips for today's open plan tasks and matching topics; tapping one fills "what are you working on". */
@Composable
fun TaskChoices(tasks: List<PlanBlock>, topics: List<Topic>, onTask: (PlanBlock) -> Unit, onTopic: (Topic) -> Unit) {
    if (tasks.isNotEmpty()) {
        SectionLabel("From today's plan")
        tasks.take(5).forEach { block ->
            FilterChip(
                selected = false, onClick = { onTask(block) },
                label = { Text("${block.title} · ${block.minutes} min") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    if (topics.isNotEmpty()) {
        SectionLabel("Topics")
        topics.forEach { topic ->
            FilterChip(
                selected = false, onClick = { onTopic(topic) }, label = { Text(topic.title) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Today's log: total and the sessions. */
@Composable
fun TodayLog(minutes: Int, sessions: List<FocusSession>) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Focused today")
        Text(
            if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min",
            style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink,
        )
        if (sessions.isEmpty()) {
            Text("No sessions yet today.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        sessions.take(8).forEach { s ->
            Text(
                "${s.minutes} min · ${s.style} · ${s.completionPct}% finished",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
        }
    }
}

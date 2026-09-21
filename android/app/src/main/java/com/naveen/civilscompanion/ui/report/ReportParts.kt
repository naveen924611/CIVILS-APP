package com.naveen.civilscompanion.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.tests.TBar

/** The numbers of the week: planned vs done time with a bar for each day, cards, questions. */
@Composable
fun NumbersCard(d: ReportData) {
    val h = d.hours
    CcCard(Modifier.fillMaxWidth()) {
        Text("This week in numbers", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(
            "Study time: ${ReportLogic.hoursText(h.doneMinutes)} done of ${ReportLogic.hoursText(h.plannedMinutes)} planned (${ReportLogic.donePercent(h)} percent)",
            style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink,
        )
        TBar(ReportLogic.donePercent(h) / 100f)
        h.byDay.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ReportLogic.dayLabel(day.day), style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted, modifier = Modifier.weight(0.12f))
                TBar(if (day.planned <= 0) 0f else day.done.toFloat() / day.planned, Modifier.weight(0.6f))
                Text("${day.done} of ${day.planned} min", style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted, modifier = Modifier.weight(0.28f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Focus timer: ${ReportLogic.hoursText(h.focusMinutes)}")
            Pill("Topics finished: ${d.topicsFinished.size}", tone = 1)
            Pill("Cards revised: ${d.cards.revised}")
        }
        val m = d.mcq
        Text(
            if (m.attempted == 0) "No practice questions answered this week."
            else "Questions answered: ${m.attempted}. Accuracy: ${Math.round(m.accuracy * 100)} percent.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
        )
        m.tests.forEach { t ->
            val score = t.score
            Text(
                "${t.title}: " + (if (score == null) "no score" else "${com.naveen.civilscompanion.ui.tests.TestLogic.scoreText(score)} of ${t.total}"),
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
    }
}

@Composable
fun CoveredCard(d: ReportData) {
    if (d.covered.isEmpty()) return
    CcCard(Modifier.fillMaxWidth()) {
        Text("Covered this week", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        d.covered.forEach { c ->
            Text(c.title + if (c.subject.isNotBlank()) "  (${c.subject})" else "", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        }
    }
}

@Composable
fun WeakCard(d: ReportData) {
    val w = d.weakSpots
    CcCard(Modifier.fillMaxWidth()) {
        Text("Weak spots", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        if (w.topics.isEmpty() && w.fadingCards == 0 && w.lowDays.isEmpty()) {
            Text("Nothing stands out. Good week.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
        w.topics.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.title, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(0.5f))
                TBar(t.strength.toFloat(), Modifier.weight(0.35f), color = Cc.colors.onDangerTint)
                Text("${Math.round(t.strength * 100)}%", style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted, modifier = Modifier.weight(0.15f))
            }
        }
        if (w.fadingCards > 0) Text("${w.fadingCards} cards are fading: they are overdue for revision.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        if (w.lowDays.isNotEmpty()) Text("Low days: " + w.lowDays.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
    }
}

/** Next week's changes with the "Accept next week's plan" button. */
@Composable
fun NextWeekCard(d: ReportData, accepted: Boolean, onAccept: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("Next week adjusts", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        d.nextWeek.changes.forEach { Text("- $it", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink) }
        if (accepted) Pill("Accepted", tone = 1)
        else BigButton("Accept next week's plan", onClick = onAccept)
    }
}

/** Last-month mode: 30 days or fewer before an exam, revision uses sheets plus due cards. */
@Composable
fun LastMonthCard(days: Int, exam: String, onSheets: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Pill("Last-month mode", tone = 2)
        Text(
            (if (exam.isBlank()) "Your exam" else exam) + (if (days == 0) " is today." else " is in $days days."),
            style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink,
        )
        Text(
            "Revision now switches to revision sheets (8 to 10 a day) plus your due cards. The planner adds 30 extra revision minutes to each day.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        BigButton("Open today's sheets", onClick = onSheets, filled = false)
    }
}

package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel

/** Separate Telugu progress: streak, the last 14 days, and how far each kind of practice has come. */
@Composable
fun TeluguProgressTab(ui: TeluguUi) {
    val s = ui.stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Stat("${s.streak}", if (s.streak == 1) "day in a row" else "days in a row")
                Stat("${s.totalDone}", "things practised")
            }
        }
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Last 14 days")
            val biggest = (s.recent.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.recent.forEach { d ->
                    val h = if (d.count == 0) 4.dp else (10 + 70 * d.count / biggest).dp
                    Box(
                        Modifier.weight(1f).height(h).clip(RoundedCornerShape(4.dp))
                            .background(if (d.count == 0) Cc.colors.border else Cc.colors.primary),
                    )
                }
            }
            Text("Taller bars mean more practice that day.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        TeluguPlan.KINDS.forEach { kind ->
            val k = s.kinds[kind] ?: KindStat()
            CcCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(TeluguPlan.kindLabel(kind), style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                    Pill("${k.practised} of ${k.items} tried", tone = if (k.items > 0 && k.practised == k.items) 1 else 0)
                    Pill("Average " + TeluguPlan.percent(k.avgScore), tone = 0)
                }
            }
        }
    }
}

@Composable
private fun Stat(big: String, small: String) {
    Column {
        Text(big, style = MaterialTheme.typography.displaySmall, color = Cc.colors.primary)
        Text(small, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
    }
}

/** Everything the owner has sent for feedback, newest first. Tap one to see the feedback again. */
@Composable
fun WritingHistoryTab(ui: TeluguUi, onOpen: (String) -> Unit) {
    if (ui.writing.isEmpty()) {
        EmptyState("Nothing written yet", "When you send a translation or a letter for feedback, it shows up here.")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.writing.forEach { w ->
            CcCard(Modifier.fillMaxWidth(), onClick = { onOpen(w.item.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val title = if (w.item.kind == "template") TeluguContent.template(w.item.content).titleEn
                    else TeluguContent.translation(w.item.content).let { if (it.toTelugu) it.en else it.reference }
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                    when (w.state) {
                        "done" -> Pill("${Math.round((w.feedback?.score ?: 0.0) * 10) / 10.0} / 10", tone = 1)
                        "failed" -> Pill("Could not be checked", tone = 3)
                        else -> Pill("Waiting", tone = 2)
                    }
                }
                TeluguText(w.progress.answer.take(120), size = 16.sp)
            }
        }
    }
}

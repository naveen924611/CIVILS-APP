package com.naveen.civilscompanion.ui.explain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** The feedback of one explanation: what was covered, missed and wrong, with the follow-up buttons. */
@Composable
fun ExplainFeedbackView(
    feedback: ExplainLogic.Feedback,
    cardsMade: Int,
    speakingModel: Boolean,
    onMakeCards: () -> Unit,
    onTryAgain: () -> Unit,
    onHearModel: () -> Unit,
) {
    val c = Cc.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ExplainLogic.coverageText(feedback), style = MaterialTheme.typography.headlineSmall, color = c.ink)
                Pill(if (feedback.fromMaterial) "From your notes" else "From general knowledge", tone = if (feedback.fromMaterial) 1 else 2)
            }
        }
        PointsCard("You explained well", feedback.covered, "Nothing from the key points yet. Try again after reading the notes.", tone = 1)
        PointsCard("You missed", feedback.missed, "Nothing missed. Well done.", tone = 2)
        if (feedback.fixes.isNotEmpty()) {
            CcCard(Modifier.fillMaxWidth()) {
                Text("Needs correcting", style = MaterialTheme.typography.titleMedium, color = c.ink)
                feedback.fixes.forEach { fix ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (fix.said.isNotEmpty()) Text("You said: ${fix.said}", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        if (fix.correct.isNotEmpty()) Text("Correct: ${fix.correct}", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    }
                }
            }
        }
        if (feedback.model.isNotEmpty()) {
            CcCard(Modifier.fillMaxWidth()) {
                Text("A model explanation", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(feedback.model, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (feedback.missed.isNotEmpty()) {
                BigButton(if (cardsMade > 0) "Cards made ($cardsMade)" else "Make cards from missed points", onClick = onMakeCards, enabled = cardsMade == 0)
            }
            BigButton("Try again", onClick = onTryAgain, filled = false)
            if (feedback.model.isNotEmpty()) {
                BigButton(if (speakingModel) "Stop" else "Hear a model explanation", onClick = onHearModel, filled = false)
            }
        }
    }
}

@Composable
private fun PointsCard(title: String, points: List<String>, emptyText: String, tone: Int) {
    val c = Cc.colors
    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Pill(points.size.toString(), tone = tone)
        }
        if (points.isEmpty()) Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        points.forEach { p ->
            Row(Modifier.padding(top = 2.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = c.primary, modifier = Modifier.width(22.dp))
                Text(p, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
    }
}

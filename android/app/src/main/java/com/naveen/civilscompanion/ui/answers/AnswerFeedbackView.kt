package com.naveen.civilscompanion.ui.answers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.ask.ActionText
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** The evaluation of one written answer (spec 6.21): score, structure, coverage, examples, length, presentation, model outline. */
@Composable
fun AnswerFeedbackView(feedback: AnswerLogic.Feedback, score: Double?, wordLimit: Int) {
    val c = Cc.colors
    var showText by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AnswerLogic.scoreLabel(score), style = MaterialTheme.typography.displaySmall, color = c.ink)
                Pill(
                    when (AnswerLogic.scoreTone(score)) {
                        1 -> "Good answer"
                        2 -> "Fair, can improve"
                        else -> "Needs work"
                    },
                    tone = AnswerLogic.scoreTone(score),
                )
            }
            if (!feedback.readable) {
                Text(
                    "The handwriting could not be read well, so the score is low. Write a little larger and take the photo in good light.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onDangerTint,
                )
            }
        }
        CcCard(Modifier.fillMaxWidth()) {
            Text("Structure", style = MaterialTheme.typography.titleMedium, color = c.ink)
            LabelledLine("Introduction", feedback.intro)
            LabelledLine("Body", feedback.body)
            LabelledLine("Conclusion", feedback.conclusion)
        }
        CcCard(Modifier.fillMaxWidth()) {
            LabelledLine("Content covered", feedback.coverage)
            LabelledLine("Examples and data", feedback.examples)
            LabelledLine("Presentation", feedback.presentation)
        }
        val words = feedback.words
        val limit = feedback.limit ?: wordLimit
        if (words != null) {
            CcCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Length", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Pill("$words words of $limit", tone = AnswerLogic.lengthTone(words, limit))
                }
                if (feedback.lengthComment.isNotEmpty()) Text(feedback.lengthComment, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        BulletCard("What went well", feedback.strengths)
        BulletCard("Improve next time", feedback.improvements)
        if (feedback.outline.isNotEmpty()) {
            CcCard(Modifier.fillMaxWidth()) {
                Text("A model answer outline", style = MaterialTheme.typography.titleMedium, color = c.ink)
                feedback.outline.forEachIndexed { i, line ->
                    Row(Modifier.padding(top = 2.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, color = c.primary, modifier = Modifier.width(30.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    }
                }
            }
        }
        if (feedback.transcript.isNotEmpty()) {
            CcCard(Modifier.fillMaxWidth()) {
                ActionText(if (showText) "Hide what the tablet read" else "Show what the tablet read", onClick = { showText = !showText })
                if (showText) Text(feedback.transcript, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
        }
    }
}

@Composable
private fun LabelledLine(label: String, text: String) {
    if (text.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Cc.colors.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
    }
}

@Composable
private fun BulletCard(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    CcCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        lines.forEach { line ->
            Row(Modifier.padding(top = 2.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.primary, modifier = Modifier.width(22.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
            }
        }
    }
}

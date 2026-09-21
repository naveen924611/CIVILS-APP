package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.NotoSansTelugu
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** Telugu text in the Telugu font, with enough line height so the letters are not cut. */
@Composable
fun TeluguText(text: String, modifier: Modifier = Modifier, size: TextUnit = 20.sp, bold: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        color = Cc.colors.ink,
        style = TextStyle(
            fontFamily = NotoSansTelugu,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            fontSize = size,
            lineHeight = size * 1.6f,
        ),
    )
}

/** A quiet button that reads Telugu text aloud. */
@Composable
fun HearButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Hear it") {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text(label, color = Cc.colors.primary) }
}

/** The small note that reminds the owner this practice is not from the official syllabus. */
@Composable
fun GeneralPracticeNote(modifier: Modifier = Modifier) {
    Text(
        "General practice, not from the official syllabus. The Telugu has not been checked by a teacher yet.",
        style = MaterialTheme.typography.bodySmall,
        color = Cc.colors.muted,
        modifier = modifier,
    )
}

/** The tutor's feedback on a piece of writing (from the `telugu_feedback` job). */
@Composable
fun FeedbackView(feedback: TeluguFeedback, modifier: Modifier = Modifier) {
    CcCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tutor feedback", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            val tone = if (feedback.score >= 7.0) 1 else if (feedback.score >= 4.0) 2 else 3
            Pill("${Math.round(feedback.score * 10) / 10.0} / 10", tone = tone)
        }
        if (feedback.summary.isNotBlank()) {
            Text(feedback.summary, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        }
        if (feedback.strengths.isNotEmpty()) {
            Text("What went well", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            feedback.strengths.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink) }
        }
        if (feedback.corrections.isNotEmpty()) {
            Text("Small fixes", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            feedback.corrections.forEach { c ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (c.said.isNotBlank()) TeluguText(c.said, size = 18.sp)
                    if (c.better.isNotBlank()) TeluguText(c.better, size = 18.sp, bold = true)
                    if (c.why.isNotBlank()) Text(c.why, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
        if (feedback.modelAnswer.isNotBlank()) {
            Text("A good answer", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            TeluguText(feedback.modelAnswer, size = 18.sp)
        }
    }
}

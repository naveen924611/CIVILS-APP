package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact

private const val LETTERS = "ABCD"

/** One question with its four options. Nothing tells right from wrong while the test runs. */
@Composable
fun QuestionView(
    number: Int,
    total: Int,
    q: Mcq,
    chosen: Int?,
    confidence: String?,
    flagged: Boolean,
    onChoose: (Int) -> Unit,
    onConfidence: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Question $number of $total")
            if (flagged) Text("Marked to look at again", style = MaterialTheme.typography.labelSmall, color = Cc.colors.onAccentTint)
        }
        Text(q.question, style = MaterialTheme.typography.titleLarge, color = Cc.colors.ink)
        q.options.take(4).forEachIndexed { i, text ->
            OptionRow(LETTERS[i].toString(), text, selected = chosen == i, onClick = { onChoose(i) })
        }
        if (chosen != null) {
            Text("How sure are you?", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TestLogic.CONFIDENCES.forEach { c ->
                    TChip(TestLogic.confidenceLabel(c), selected = confidence == c, onClick = { onConfidence(c) })
                }
            }
        }
    }
}

@Composable
private fun OptionRow(letter: String, text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Cc.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) c.primaryTint else c.surface)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = c.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
    }
}

/** Timer, counts and a grid of question numbers (green = answered, amber = marked, grey = empty). */
@Composable
fun RunSidePanel(
    questions: List<Mcq>,
    run: LocalTestRun,
    secondsLeft: Long,
    onGoTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val answered = questions.count { run.chosen.containsKey(it.id) }
    CcCard(modifier) {
        SectionLabel("Time left")
        Text(
            TestLogic.clock(secondsLeft), style = MaterialTheme.typography.displaySmall,
            color = if (secondsLeft in 0L..300L) Cc.colors.onDangerTint else Cc.colors.ink,
        )
        Text("$answered of ${questions.size} answered", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isCompact()) 10 else 5),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(questions, key = { _, q -> q.id }) { i, q ->
                val isAnswered = run.chosen.containsKey(q.id)
                val isFlagged = q.id in run.flagged
                val bg = when {
                    isFlagged -> Cc.colors.accentTint
                    isAnswered -> Cc.colors.primaryTint
                    else -> Cc.colors.rail
                }
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .border(BorderStroke(if (i == run.index) 2.dp else 0.dp, Cc.colors.primary), RoundedCornerShape(10.dp))
                        .clickable { onGoTo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Cc.colors.ink)
                }
            }
        }
    }
}

/** Shown before the test starts. */
@Composable
fun RunIntro(
    title: String,
    questions: Int,
    minutes: Int,
    negative: Boolean,
    onNegative: (Boolean) -> Unit,
    isRetest: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(if (isCompact()) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        CcCard(Modifier.fillMaxWidth()) {
            Text("$questions questions, $minutes minutes.", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            Text(
                "The timer starts when you press Start. Your answers are saved as you go, so you can leave and come back. " +
                    "After each answer, say how sure you are: this helps the analysis afterwards.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
            )
            if (!isRetest) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Switch(checked = negative, onCheckedChange = onNegative)
                    Text(
                        "Negative marking (one third of a mark lost for each wrong answer)",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        content()
    }
}

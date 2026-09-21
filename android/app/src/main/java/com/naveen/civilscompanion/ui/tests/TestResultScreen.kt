package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes

/** Result of a test: score, subject bars, mistake types, guessing analysis, and every question reviewed. Route test/{testId}/result. */
@Composable
fun TestResultScreen(nav: NavHostController, testId: String, vm: TestResultViewModel = hiltViewModel()) {
    LaunchedEffect(testId) { vm.load(testId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val analysis = state.analysis
    Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
        when {
            state.loading -> Text("Working out your result...", modifier = Modifier.padding(24.dp), color = Cc.colors.muted)
            analysis == null -> Column(Modifier.fillMaxSize()) {
                EmptyState("No result", state.error ?: "Nothing to show.", Modifier.weight(1f))
                BigButton("Back to tests", onClick = { nav.navigate(Routes.TESTS) }, modifier = Modifier.padding(24.dp))
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    ScreenTitle(
                        state.title, subtitle = "Your result",
                        actions = {
                            if (analysis.wrong > 0) BigButton("Retest my mistakes", onClick = { nav.navigate(Routes.testRun(RETEST_ID)) })
                            BigButton("All tests", onClick = { nav.navigate(Routes.TESTS) }, filled = false)
                        },
                    )
                }
                item { ScoreCard(analysis) }
                item { SubjectsCard(analysis) }
                item { MistakesCard(analysis) }
                item { GuessCard(analysis) }
                item { SectionLabel("Review every question") }
                itemsIndexed(state.items, key = { _, a -> a.mcq.id }) { i, a ->
                    ReviewCard(i + 1, a, onType = { vm.setType(a.mcq.id, it) })
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(a: Analysis) {
    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${TestLogic.scoreText(a.score)} / ${a.total}", style = MaterialTheme.typography.displayMedium, color = Cc.colors.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${a.correct} right, ${a.wrong} wrong, ${a.skipped} skipped. Accuracy ${TestLogic.percent(a.correct, a.total)} percent.",
                    style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink,
                )
                Text(
                    if (a.negative) "Negative marking was on." else "Negative marking was off.",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                )
            }
        }
    }
}

@Composable
private fun SubjectsCard(a: Analysis) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("By subject", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        a.subjects.forEach { s ->
            val pct = TestLogic.percent(s.correct, s.total)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.subject, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(0.35f))
                TBar(pct / 100f, Modifier.weight(0.45f), color = if (pct < 50) Cc.colors.onDangerTint else Cc.colors.primary)
                Text("${s.correct}/${s.total}", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted, modifier = Modifier.weight(0.2f))
            }
        }
    }
}

@Composable
private fun MistakesCard(a: Analysis) {
    if (a.wrong == 0) return
    CcCard(Modifier.fillMaxWidth()) {
        Text("Your mistakes", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestLogic.MISTAKE_TYPES.forEach { t ->
                Pill("${TestLogic.mistakeLabel(t)}: ${a.mistakes[t] ?: 0}", tone = if (t == TestLogic.SILLY) 2 else if (t == TestLogic.DIDNT_KNOW) 3 else 0)
            }
        }
        if (a.studyTopics.isNotEmpty()) {
            Text(
                "To study again: " + a.studyTopics.joinToString(", ") { "${it.first} (${it.second})" },
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
        }
        a.tips.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted) }
        Text("You can change the type of a mistake in the review below.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
}

@Composable
private fun GuessCard(a: Analysis) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("Guessing", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(a.guessMessage, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestLogic.CONFIDENCES.forEach { c ->
                val b = a.confidence[c]
                if (b != null && b.answered > 0) Pill("${TestLogic.confidenceLabel(c)}: ${b.correct} of ${b.answered} right")
            }
        }
    }
}

@Composable
private fun ReviewCard(number: Int, a: Answered, onType: (String) -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Question $number", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            when {
                a.correct -> Pill("Right", tone = 1)
                a.skipped -> Pill("Skipped", tone = 2)
                else -> Pill("Wrong", tone = 3)
            }
            if (a.confidence.isNotBlank()) Pill(TestLogic.confidenceLabel(a.confidence))
        }
        Text(a.mcq.question, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        a.mcq.options.take(4).forEachIndexed { i, text ->
            val mark = when {
                i == a.mcq.answerIndex -> "  (correct answer)"
                i == a.chosen -> "  (your answer)"
                else -> ""
            }
            Text(
                "${"ABCD"[i]}) $text$mark", style = MaterialTheme.typography.bodyMedium,
                color = when {
                    i == a.mcq.answerIndex -> Cc.colors.primary
                    i == a.chosen -> Cc.colors.onDangerTint
                    else -> Cc.colors.ink
                },
            )
        }
        if (a.mcq.explanation.isNotBlank()) Text(a.mcq.explanation, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        if (a.wrong) {
            Text("What kind of mistake was this?", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TestLogic.MISTAKE_TYPES.forEach { t -> TChip(TestLogic.mistakeLabel(t), selected = a.mistakeType == t, onClick = { onType(t) }) }
            }
        }
    }
}

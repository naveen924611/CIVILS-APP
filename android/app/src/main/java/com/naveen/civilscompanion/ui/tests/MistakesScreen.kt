package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** The mistake book: every wrong answer with the right answer and the reason. Route mistakes. */
@Composable
fun MistakesScreen(nav: NavHostController, vm: MistakesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val subject by vm.subject.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val compact = isCompact()

    LazyColumn(
        Modifier.fillMaxSize().background(Cc.colors.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = if (compact) 16.dp else 24.dp, top = if (compact) 16.dp else 24.dp, end = if (compact) 16.dp else 24.dp, bottom = if (compact) 88.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScreenTitle(
                        "Mistake book",
                        subtitle = "A question leaves the book after you answer it correctly twice, on different days. ${state.cleared} cleared so far.",
                    )
                    BigButton("Retest mistakes (${state.due} due)", onClick = { nav.navigate(Routes.testRun(RETEST_ID)) }, enabled = state.total > 0)
                }
            } else {
                ScreenTitle(
                    "Mistake book",
                    subtitle = "A question leaves the book after you answer it correctly twice, on different days. ${state.cleared} cleared so far.",
                    actions = {
                        BigButton("Retest mistakes (${state.due} due)", onClick = { nav.navigate(Routes.testRun(RETEST_ID)) }, enabled = state.total > 0)
                    },
                )
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TChip("All types", selected = type == null, onClick = { vm.type.value = null })
                TestLogic.MISTAKE_TYPES.forEach { t -> TChip(TestLogic.mistakeLabel(t), selected = type == t, onClick = { vm.type.value = t }) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TChip("All subjects", selected = subject == null, onClick = { vm.subject.value = null })
                state.subjects.forEach { s -> TChip(s, selected = subject == s, onClick = { vm.subject.value = s }) }
            }
        }
        if (state.rows.isEmpty()) {
            item {
                Text(
                    if (state.total == 0) "Nothing here yet. Wrong answers from your tests are collected in this book." else "No mistakes match these filters.",
                    style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(state.rows, key = { it.mistake.id }) { row -> MistakeCard(row) }
    }
}

@Composable
private fun MistakeCard(row: MistakeRow) {
    val q = row.mcq
    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(row.subject)
            Pill(TestLogic.mistakeLabel(row.mistake.mistakeType), tone = if (row.mistake.mistakeType == TestLogic.SILLY) 2 else 3)
            if (row.due) Pill("Due", tone = 2)
            if (row.mistake.streak > 0) Pill("Right ${row.mistake.streak} of 2", tone = 1)
        }
        Text(q.question, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        val yours = row.mistake.yourAnswer
        if (yours in q.options.indices) {
            Text("Your answer: ${"ABCD"[yours]}) ${q.options[yours]}", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onDangerTint)
        }
        if (q.answerIndex in q.options.indices) {
            Text("Right answer: ${"ABCD"[q.answerIndex]}) ${q.options[q.answerIndex]}", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.primary)
        }
        if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
}

package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes
import kotlinx.coroutines.delay

/** Take a test: timer, question by question, a grid to jump around, finish at any time. Route test/{testId}. */
@Composable
fun TestRunScreen(nav: NavHostController, testId: String, vm: TestRunViewModel = hiltViewModel()) {
    LaunchedEffect(testId) { vm.load(testId) }
    LaunchedEffect(Unit) {
        vm.finished.collect { id ->
            nav.navigate(Routes.testResult(id)) { popUpTo(Routes.TESTS) }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val run = state.run
    Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
        when {
            state.loading -> Text("Opening the test...", modifier = Modifier.padding(24.dp), color = Cc.colors.muted)
            state.error != null -> Column(Modifier.fillMaxSize()) {
                EmptyState("Not ready", state.error.orEmpty(), Modifier.weight(1f))
                BigButton("Back to tests", onClick = { nav.popBackStack() }, modifier = Modifier.padding(24.dp))
            }
            run == null -> Text("Nothing to show.", modifier = Modifier.padding(24.dp), color = Cc.colors.muted)
            run.startedAtMs == 0L -> StartPage(nav, state, run, vm)
            else -> RunPage(nav, state, run, vm)
        }
    }
}

@Composable
private fun StartPage(nav: NavHostController, state: RunState, run: LocalTestRun, vm: TestRunViewModel) {
    var negative by remember(run.id) { mutableStateOf(run.negative) }
    RunIntro(
        title = state.title, questions = state.questions.size, minutes = run.durationMin, negative = negative,
        onNegative = { negative = it }, isRetest = !state.isRealTest, modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        if (state.alreadyDone) {
            Text("You already took this test. You can see the result, or take it again for practice.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            BigButton("See my result", onClick = { nav.navigate(Routes.testResult(run.id)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (state.alreadyDone) "Take it again" else "Start", onClick = { vm.start(negative) }, filled = !state.alreadyDone)
            BigButton("Back", onClick = { nav.popBackStack() }, filled = false)
        }
    }
}

@Composable
private fun RunPage(nav: NavHostController, state: RunState, run: LocalTestRun, vm: TestRunViewModel) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(run.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secondsLeft = TestLogic.remainingSeconds(run.startedAtMs, run.durationMin, now)
    val timeUp = secondsLeft <= 0
    LaunchedEffect(timeUp) { if (timeUp) vm.finish() }

    val questions = state.questions
    val index = run.index.coerceIn(0, (questions.size - 1).coerceAtLeast(0))
    val q = questions[index]
    val unanswered = questions.count { !run.chosen.containsKey(it.id) }

    if (isCompact()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            RunSidePanel(questions, run, secondsLeft, onGoTo = vm::goTo, modifier = Modifier.fillMaxWidth())
            QuestionView(
                number = index + 1, total = questions.size, q = q, chosen = run.chosen[q.id], confidence = run.confidence[q.id],
                flagged = q.id in run.flagged, onChoose = { vm.choose(q.id, it) }, onConfidence = { vm.setConfidence(q.id, it) },
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("Previous", onClick = { vm.goTo(index - 1) }, filled = false, enabled = index > 0)
                BigButton("Next", onClick = { vm.goTo(index + 1) }, filled = false, enabled = index < questions.size - 1)
                BigButton(if (q.id in run.flagged) "Unmark" else "Mark to look again", onClick = { vm.toggleFlag(q.id) }, filled = false)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    BigButton("Finish test", onClick = { confirm = true })
                }
            }
        }
    } else Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            QuestionView(
                number = index + 1, total = questions.size, q = q, chosen = run.chosen[q.id], confidence = run.confidence[q.id],
                flagged = q.id in run.flagged, onChoose = { vm.choose(q.id, it) }, onConfidence = { vm.setConfidence(q.id, it) },
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("Previous", onClick = { vm.goTo(index - 1) }, filled = false, enabled = index > 0)
                BigButton("Next", onClick = { vm.goTo(index + 1) }, filled = false, enabled = index < questions.size - 1)
                BigButton(if (q.id in run.flagged) "Unmark" else "Mark to look again", onClick = { vm.toggleFlag(q.id) }, filled = false)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    BigButton("Finish test", onClick = { confirm = true })
                }
            }
        }
        RunSidePanel(questions, run, secondsLeft, onGoTo = vm::goTo, modifier = Modifier.width(300.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Finish the test?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    if (unanswered == 0) "You answered every question." else "$unanswered question${if (unanswered != 1) "s are" else " is"} not answered yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.finish()
                }) { Text("Finish", color = Cc.colors.primary) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going", color = Cc.colors.muted) } },
        )
    }
}

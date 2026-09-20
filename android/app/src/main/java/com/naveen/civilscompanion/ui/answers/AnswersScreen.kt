package com.naveen.civilscompanion.ui.answers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.AnswerSubmission
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.ask.ActionText
import com.naveen.civilscompanion.ui.ask.AskLogic
import com.naveen.civilscompanion.ui.ask.ChipButton
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes

/** Answer writing, first page: practice questions to write, answers waiting for feedback, and the history of scores. */
@Composable
fun AnswersScreen(nav: NavHostController, vm: AnswersViewModel = hiltViewModel()) {
    val answers by vm.answers.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showOwn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.opened.collect { id -> nav.navigate(Routes.answer(id)) } }

    val toWrite = answers.filter { it.status == "draft" }
    val waiting = answers.filter { it.status == "queued" }
    val done = answers.filter { it.status == "done" }
    val failed = answers.filter { it.status == "failed" }

    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Answer writing", subtitle = "Practise Mains answers: write, take a photo, get feedback.")
        message?.let { msg ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                ActionText("OK", onClick = vm::clearMessage)
            }
        }
        CcCard(Modifier.fillMaxWidth()) {
            Text("Get a question", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipButton(if (busy) "Please wait..." else "Short (150 words)", selected = false, onClick = { if (!busy) vm.generate("short") })
                ChipButton("Mains (250 words)", selected = false, onClick = { if (!busy) vm.generate("mains") })
                ChipButton("Essay (1000 words)", selected = false, onClick = { if (!busy) vm.generate("essay") })
                ActionText("Write my own question", onClick = { showOwn = true })
            }
            Text("New questions need the internet. Your own questions work offline.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        ScoreTrend(done)
        Section("To write", toWrite, "No question waiting. Get one above.", nav)
        Section("Waiting for feedback", waiting, "", nav)
        Section("Feedback ready", done, "Your scored answers will appear here.", nav)
        Section("Could not be checked", failed, "", nav)
    }

    if (showOwn) OwnQuestionDialog(onDismiss = { showOwn = false }, onCreate = { q, limit ->
        showOwn = false
        vm.createOwn(q, limit)
    })
}

@Composable
private fun Section(title: String, rows: List<AnswerSubmission>, emptyText: String, nav: NavHostController) {
    if (rows.isEmpty() && emptyText.isEmpty()) return
    SectionLabel(title)
    if (rows.isEmpty()) Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
    rows.forEach { a ->
        CcCard(onClick = { nav.navigate(Routes.answer(a.id)) }, modifier = Modifier.fillMaxWidth()) {
            Text(a.question, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(AnswerLogic.kindLabel(a.kind))
                Pill("${a.wordLimit} words")
                if (a.status == "done") Pill(AnswerLogic.scoreLabel(a.score), tone = AnswerLogic.scoreTone(a.score))
                if (a.status == "queued") Pill("Waiting", tone = 2)
                if (a.status == "failed") Pill("Failed", tone = 3)
                Text(AskLogic.dayTimeLabel(a.createdAt), style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
            }
        }
    }
}

/** A row of little bars: the last scores, oldest first, so improvement is easy to see. */
@Composable
private fun ScoreTrend(done: List<AnswerSubmission>) {
    val scores = done.mapNotNull { it.score }.take(12).reversed()
    if (scores.size < 2) return
    val heights = AnswerLogic.trend(scores)
    CcCard(Modifier.fillMaxWidth()) {
        Text("Your scores over time", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Row(Modifier.height(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            heights.forEachIndexed { i, h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxHeight()) {
                    Text(String.format(java.util.Locale.ENGLISH, "%.1f", scores[i]), style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
                    Box(Modifier.width(28.dp).height((60 * h).dp).clip(RoundedCornerShape(4.dp)).background(Cc.colors.primary))
                }
            }
        }
    }
}

@Composable
private fun OwnQuestionDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(250) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your own question") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type the question") }, minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(150, 250, 1000).forEach { n -> ChipButton("$n words", selected = limit == n, onClick = { limit = n }) }
                }
            }
        },
        confirmButton = { BigButton("Start", onClick = { onCreate(text, limit) }, enabled = text.trim().length >= 10) },
        dismissButton = { ActionText("Cancel", onClick = onDismiss) },
    )
}

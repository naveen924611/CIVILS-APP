package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.MockTest
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes

/** Mock tests: weekly mock, topic tests, past papers, the mistakes retest and the history of scores. */
@Composable
fun TestsScreen(nav: NavHostController, vm: TestsViewModel = hiltViewModel()) {
    val tests by vm.tests.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf("") }

    val ready = tests.filter { it.status == "ready" || it.status == "in_progress" }
    val taken = tests.filter { it.status == "done" || it.status == "analysed" }

    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(
            "Mock tests",
            subtitle = "Weekly mock every Sunday, topic tests and past papers. They work without internet.",
            actions = { BigButton("Mistake book", onClick = { nav.navigate(Routes.MISTAKES) }, filled = false) },
        )
        message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TNotice(msg, Modifier.weight(1f))
                TAction("OK", onClick = vm::clearMessage)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Ready to take")
                if (ready.isEmpty()) {
                    Text(
                        "No test is ready. The weekly mock arrives on Sunday morning. You can also make one below.",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                    )
                }
                ready.forEach { t -> TestCard(t, onOpen = { nav.navigate(Routes.testRun(t.id)) }) }
                pending.forEach { j -> JobCard(j, onDismiss = { vm.dismiss(j) }) }
                SectionLabel("Taken")
                if (taken.isEmpty()) Text("Your finished tests will be listed here.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                taken.forEach { t -> TestCard(t, onOpen = { nav.navigate(Routes.testResult(t.id)) }) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CcCard(Modifier.fillMaxWidth()) {
                    Text("Retest my mistakes", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                    Text(
                        if (counts.open == 0) "Your mistake book is empty. Wrong answers from tests are collected there."
                        else "${counts.open} in your book, ${counts.due} due now. Answer correctly twice, days apart, and a question leaves the book.",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                    )
                    BigButton("Start mistakes retest", onClick = { nav.navigate(Routes.testRun(RETEST_ID)) }, enabled = counts.open > 0)
                }
                CcCard(Modifier.fillMaxWidth()) {
                    Text("Make a test", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                    Text("Tests are made on the server, so they need the internet once. After that they work offline.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    BigButton("Weekly mock now (25 questions)", onClick = vm::makeWeekly, filled = false, modifier = Modifier.fillMaxWidth())
                    BigButton("Topic test", onClick = { dialog = "topic" }, filled = false, modifier = Modifier.fillMaxWidth())
                    BigButton("Full past paper", onClick = { dialog = "paper" }, filled = false, modifier = Modifier.fillMaxWidth())
                    BigButton("Read a past paper from my library", onClick = { dialog = "import" }, filled = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    when (dialog) {
        "topic" -> TopicTestDialog(topics, onDismiss = { dialog = "" }, onMake = { id, n ->
            dialog = ""
            vm.makeTopicTest(id, n)
        })
        "paper" -> PaperDialog(
            title = "Full past paper", help = "Only questions whose answers were printed in the paper are used.",
            documents = null, confirmText = "Make test", onDismiss = { dialog = "" },
            onConfirm = { _, exam, year, paper ->
                dialog = ""
                vm.makePastPaper(exam, year, paper)
            },
        )
        "import" -> PaperDialog(
            title = "Read a past paper", help = "Pick a paper you added to the Library. The questions are copied exactly as printed.",
            documents = documents, confirmText = "Read it", onDismiss = { dialog = "" },
            onConfirm = { docId, exam, year, paper ->
                dialog = ""
                vm.importPaper(docId, exam, year, paper)
            },
        )
    }
}

@Composable
private fun TestCard(t: MockTest, onOpen: () -> Unit) {
    CcCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Text(t.title.ifBlank { "Test" }, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(kindLabel(t.kind))
            Pill("${t.mcqIds.size} questions")
            Pill("${t.durationMin} min")
            if (t.negativeMarking) Pill("Negative marking", tone = 2)
            if (t.status == "in_progress") Pill("Started", tone = 2)
            if (t.status == "done" || t.status == "analysed") {
                val score = t.score
                if (score != null) Pill("${TestLogic.scoreText(score)} of ${t.mcqIds.size}", tone = 1)
            }
        }
        val day = TimeUtil.parse(t.scheduledFor)?.let { TimeUtil.dateOf(it) }
        if (day != null) Text(day, style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
    }
}

@Composable
private fun JobCard(j: Job, onDismiss: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        val name = when (j.type) {
            "pyq_import" -> "Reading a past paper"
            "mock_test" -> "Weekly mock test"
            else -> "A test"
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        when (j.status) {
            "failed" -> {
                Text(j.error.ifBlank { "This could not be made." }, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onDangerTint)
                TAction("Dismiss", onClick = onDismiss)
            }
            "running" -> Pill("Making it now", tone = 2)
            else -> Pill("Waiting for the internet", tone = 2)
        }
    }
}

fun kindLabel(kind: String): String = when (kind) {
    "weekly" -> "Weekly mock"
    "topic" -> "Topic test"
    "past_paper" -> "Past paper"
    "mistakes" -> "Mistakes"
    else -> "Test"
}

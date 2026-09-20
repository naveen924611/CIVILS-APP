package com.naveen.civilscompanion.ui.explain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.ExplainSession
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.ask.ActionText
import com.naveen.civilscompanion.ui.ask.AskLogic
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.rememberMicPermission
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.voice.CcPaths
import com.naveen.civilscompanion.ui.voice.PathIcon

/** Prefix used in the route argument to open an earlier explanation instead of a topic. */
const val EXPLAIN_SESSION_PREFIX = "session-"

/** Explain it back, first page: choose a topic, or look at earlier explanations. */
@Composable
fun ExplainPickerScreen(nav: NavHostController, vm: ExplainViewModel = hiltViewModel()) {
    val topics by vm.topics.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val titles by vm.topicTitles.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val shown = remember(topics, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) topics else topics.filter { it.title.lowercase().contains(q) }).take(80)
    }
    Column(Modifier.fillMaxSize().background(Cc.colors.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Explain it back", subtitle = "Teach a topic out loud, as if to a friend. The tablet checks what you covered.")
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a topic", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search topics") }, singleLine = true,
                )
                if (shown.isEmpty()) {
                    Text("No topics yet. Add your syllabus in Notes first.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { t -> TopicRow(t) { nav.navigate(Routes.explainTopic(t.id)) } }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your earlier explanations", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                if (history.isEmpty()) {
                    Text("Nothing yet. Pick a topic and explain it.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) { s ->
                        HistoryRow(s, titles[s.topicId.orEmpty()] ?: "A topic") { nav.navigate(Routes.explainTopic(EXPLAIN_SESSION_PREFIX + s.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicRow(t: Topic, onClick: () -> Unit) {
    CcCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            if (t.status == "in_progress") Pill("Studying", tone = 1)
        }
    }
}

@Composable
private fun HistoryRow(s: ExplainSession, title: String, onClick: () -> Unit) {
    val feedback = ExplainLogic.parseFeedback(s.feedback)
    CcCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                feedback != null -> Pill(ExplainLogic.coverageText(feedback), tone = 1)
                s.status == "failed" -> Pill("Could not check", tone = 3)
                else -> Pill("Waiting for feedback", tone = 2)
            }
            Text(AskLogic.dayTimeLabel(s.createdAt), style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
        }
    }
}

/** Record (or type) an explanation of one topic, send it, and read the feedback. `arg` is a topic id, or "session-<id>". */
@Composable
fun ExplainScreen(nav: NavHostController, arg: String, vm: ExplainViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val requestMic = rememberMicPermission(onDenied = vm::micDenied) { vm.startRecording() }

    LaunchedEffect(arg) {
        if (arg.startsWith(EXPLAIN_SESSION_PREFIX)) vm.open(arg.removePrefix(EXPLAIN_SESSION_PREFIX)) else vm.start(arg)
    }

    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(
            form.topicTitle.ifEmpty { "Explain it back" },
            subtitle = "Explain it as if you are teaching a friend. Two to three minutes is enough.",
            actions = { ActionText("All topics", onClick = { nav.navigate(Routes.EXPLAIN) }) },
        )
        form.notice?.let { msg ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                ActionText("OK", onClick = vm::clearNotice)
            }
        }
        val s = session
        val feedback = s?.let { ExplainLogic.parseFeedback(it.feedback) }
        when {
            s == null -> RecordPane(
                form = form,
                onMic = { if (form.recording) vm.stopRecording() else requestMic() },
                onText = vm::setTranscript,
                onSubmit = vm::submit,
            )
            s.status == "done" && feedback != null -> ExplainFeedbackView(
                feedback = feedback,
                cardsMade = form.cardsMade,
                speakingModel = form.speakingModel,
                onMakeCards = { vm.makeCards(s, feedback.missed) },
                onTryAgain = vm::tryAgain,
                onHearModel = { vm.toggleModel(feedback.model) },
            )
            s.status == "failed" -> CcCard(Modifier.fillMaxWidth()) {
                Text("This one could not be checked", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text("It may have been too short. Please try again and explain a little more.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                BigButton("Try again", onClick = vm::tryAgain)
            }
            else -> CcCard(Modifier.fillMaxWidth()) {
                Text("Sent for feedback", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    "The feedback arrives when the internet is on. You will get a notification. You can leave this page.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
                Text(s.transcript, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            }
        }
    }
}

@Composable
private fun RecordPane(form: ExplainForm, onMic: () -> Unit, onText: (String) -> Unit, onSubmit: () -> Unit) {
    val c = Cc.colors
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (form.recording) c.dangerTint else c.primary)
                .clickable(onClick = onMic)
                .semantics { contentDescription = if (form.recording) "Stop recording" else "Start recording" },
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(
                "mic", if (form.recording) CcPaths.STOP else CcPaths.MIC,
                tint = if (form.recording) c.onDangerTint else c.onPrimary, size = 40.dp,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (form.recording) "Recording ${ExplainLogic.durationLabel(form.seconds)} - tap to stop" else "Tap the big button and start explaining",
                style = MaterialTheme.typography.titleMedium, color = c.ink,
            )
            Text(
                if (form.recording && form.partial.isNotEmpty()) form.partial else "Your words appear below. You can fix them or type instead.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
    }
    OutlinedTextField(
        value = form.transcript,
        onValueChange = onText,
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        placeholder = { Text("What you said will appear here") },
        enabled = !form.recording,
        minLines = 6,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BigButton("Get feedback", onClick = onSubmit, enabled = !form.recording && form.transcript.isNotBlank())
        Text(
            "${ExplainLogic.wordCount(form.transcript)} words. Only the text is sent, never the sound.",
            style = MaterialTheme.typography.labelMedium, color = c.muted,
        )
    }
}

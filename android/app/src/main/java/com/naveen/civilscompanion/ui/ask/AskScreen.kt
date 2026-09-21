package com.naveen.civilscompanion.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.common.rememberMicPermission
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.voice.CcPaths
import com.naveen.civilscompanion.ui.voice.PathIcon

private val EXAMPLES = listOf(
    "What is the difference between Article 14 and Article 21?",
    "Explain the basic structure doctrine simply",
    "What are the causes of inflation in India?",
)

/** Ask (spec 6.4): chat with the tutor by typing or speaking. Works offline: questions wait in the queue. */
@Composable
fun AskScreen(nav: NavHostController, vm: AskViewModel = hiltViewModel()) {
    val bubbles by vm.bubbles.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val waitingCount by vm.waitingCount.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val topic by vm.topic.collectAsStateWithLifecycle()
    val draftNote by vm.draftNote.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val requestMic = rememberMicPermission(onDenied = vm::micDenied) { vm.toggleListening(nav) }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val compact = isCompact()
    val emptyScroll = rememberScrollState()

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex)
    }

    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (compact) {
                ScreenTitle("Ask", subtitle = "Type or speak. Answers come from your own notes and books.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionText("Explain it back", onClick = { nav.navigate(Routes.EXPLAIN) })
                    ActionText("Answer writing", onClick = { nav.navigate(Routes.ANSWERS) })
                    ActionText("Earlier chats", onClick = { showHistory = true })
                    ActionText("New chat", onClick = vm::newChat)
                }
            } else {
                ScreenTitle(
                    "Ask",
                    subtitle = "Type or speak. Answers come from your own notes and books.",
                    actions = {
                        ActionText("Explain it back", onClick = { nav.navigate(Routes.EXPLAIN) })
                        ActionText("Answer writing", onClick = { nav.navigate(Routes.ANSWERS) })
                        ActionText("Earlier chats", onClick = { showHistory = true })
                        ActionText("New chat", onClick = vm::newChat)
                    },
                )
            }
            ModeRow(mode, onPick = vm::setMode)
            if (bubbles.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().then(if (compact) Modifier.verticalScroll(emptyScroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Try asking", style = MaterialTheme.typography.titleMedium, color = Cc.colors.muted)
                    EXAMPLES.forEach { q -> CcCard(onClick = { vm.setInput(q) }, modifier = Modifier.fillMaxWidth()) { Text(q, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink) } }
                    if (compact) WaitingPanel(rows = waiting, count = waitingCount, modifier = Modifier.fillMaxWidth())
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(bubbles, key = { it.key }) { b ->
                        ChatBubble(
                            bubble = b,
                            speaking = speaking == b.key,
                            onSpeak = { vm.toggleSpeak(b) },
                            onSave = { vm.saveToNotes(b) },
                            onMore = { vm.askTutorForMore(b) },
                            onOpenSource = { route -> nav.navigate(route) },
                        )
                    }
                }
            }
            notice?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                    ActionText("OK", onClick = vm::clearNotice)
                }
            }
            if (compact && bubbles.isNotEmpty() && waitingCount > 0) {
                Text("$waitingCount waiting for internet", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            }
            if (topic != null || draftNote != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val t = topic
                    Text(
                        listOfNotNull(t?.let { "About: ${it.title}" }, draftNote?.let { "From: $it" }).joinToString("  -  "),
                        style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted,
                    )
                    ActionText("Clear", onClick = vm::clearTopic)
                }
            }
            InputBar(
                text = input,
                listening = voice.listening,
                partial = voice.partial,
                onText = vm::setInput,
                onSend = vm::send,
                onMic = { if (voice.listening) vm.toggleListening(nav) else requestMic() },
            )
        }
        if (!compact) {
            WaitingPanel(
                rows = waiting, count = waitingCount,
                modifier = Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 24.dp, end = 24.dp, bottom = 24.dp),
            )
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            confirmButton = { ActionText("Close", onClick = { showHistory = false }) },
            title = { Text("Earlier chats") },
            text = {
                if (history.isEmpty()) {
                    Text("No earlier chats yet.")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        history.forEach { h ->
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        vm.openChat(h.id)
                                        showHistory = false
                                    }.padding(8.dp),
                            ) {
                                Text(h.title, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                                Text(AskLogic.dayTimeLabel(h.at), style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ModeRow(mode: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AskLogic.MODES.forEach { (code, label) -> ChipButton(label, selected = mode == code, onClick = { onPick(code) }) }
    }
}

@Composable
private fun InputBar(
    text: String,
    listening: Boolean,
    partial: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    val c = Cc.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (listening) c.dangerTint else c.primary)
                .border(1.dp, c.border, CircleShape)
                .clickable(onClick = onMic)
                .semantics { contentDescription = if (listening) "Stop listening" else "Tap to talk" },
            contentAlignment = Alignment.Center,
        ) {
            PathIcon("mic", CcPaths.MIC, tint = if (listening) c.onDangerTint else c.onPrimary, size = 32.dp)
        }
        OutlinedTextField(
            value = if (listening && partial.isNotEmpty()) partial else text,
            onValueChange = onText,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (listening) "Listening..." else "Type your question, or tap the mic and speak") },
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        BigButton("Send", onClick = onSend, enabled = text.isNotBlank())
    }
}

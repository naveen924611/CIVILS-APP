package com.naveen.civilscompanion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** "In the news" tab: news items the server matched to this topic (written into the note by the server). */
@Composable
fun NewsTab(d: DetailState, vm: NotesViewModel, onAsk: () -> Unit) {
    val topic = d.topic ?: return
    val items = d.note?.let { NoteLogic.news(it.sections) }.orEmpty()
    val uri = LocalUriHandler.current
    if (items.isEmpty()) {
        Text(
            "Nothing in the news matches this topic yet. New items are matched every few hours, and after notes are made.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Summaries are written by AI. Open the link to check the source.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        items.forEach { item ->
            CcCard(Modifier.fillMaxWidth()) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.source.isNotBlank()) Pill(item.source)
                    if (item.publishedAt.isNotBlank()) Text(NoteLogic.shortDate(item.publishedAt), style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    if (item.url.isNotBlank()) {
                        BigButton("Open the source", onClick = {
                            try {
                                uri.openUri(item.url)
                            } catch (e: IllegalArgumentException) {
                                // no app can open this link: nothing to do
                            }
                        }, filled = false)
                    }
                    BigButton("Ask about this", onClick = {
                        vm.askAbout(topic, "How does this news relate to \"${topic.title}\"? ${item.title}")
                        onAsk()
                    }, filled = false)
                }
            }
        }
    }
}

/** "Questions" tab: the multiple-choice questions made from this topic, and its flashcards. */
@Composable
fun QuestionsTab(d: DetailState) {
    val chosen = remember(d.topic?.id) { mutableStateMapOf<String, Int>() }
    if (d.mcqs.isEmpty() && d.cards.isEmpty()) {
        Text(
            "No questions yet. They are made together with the notes, from your own material.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        d.mcqs.forEachIndexed { index, q ->
            CcCard(Modifier.fillMaxWidth()) {
                Text("${index + 1}. ${q.question}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                val pick = chosen[q.id]
                q.options.forEachIndexed { i, option ->
                    val shape = RoundedCornerShape(10.dp)
                    val right = pick != null && i == q.answerIndex
                    val wrong = pick == i && i != q.answerIndex
                    Text(
                        "${'A' + i}.  $option",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cc.colors.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (right) Cc.colors.primaryTint else if (wrong) Cc.colors.dangerTint else Cc.colors.background)
                            .border(1.dp, Cc.colors.border, shape)
                            .clickable(enabled = pick == null) { chosen[q.id] = i }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                if (pick != null && q.explanation.isNotBlank()) {
                    Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
        if (d.cards.isNotEmpty()) {
            Text("Flashcards for this topic (${d.cards.size})", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            d.cards.forEach { c ->
                CcCard(Modifier.fillMaxWidth()) {
                    Text(c.front, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    Text(c.back, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
    }
}

/** "My doubts" tab: questions the owner wants to ask later. Kept on this tablet only. */
@Composable
fun DoubtsTab(d: DetailState, vm: NotesViewModel, onAsk: () -> Unit) {
    val topic = d.topic ?: return
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Write down what you did not understand. Ask it now or later.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("My doubt") },
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        BigButton("Save this doubt", onClick = {
            vm.addDoubt(topic.id, text)
            text = ""
        }, enabled = text.isNotBlank())
        d.doubts.forEach { doubt ->
            CcCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = doubt.resolved, onCheckedChange = { vm.toggleDoubt(doubt) })
                    Text(doubt.text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (doubt.resolved) Pill("Understood", tone = 1)
                    TextButton(
                        onClick = {
                            vm.askAbout(topic, doubt.text)
                            onAsk()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Ask now") }
                    TextButton(onClick = { vm.deleteDoubt(doubt) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
            }
        }
    }
}

/** "Sources" tab: where the note came from (documents and pages of the owner's own material). */
@Composable
fun SourcesTab(d: DetailState, onOpenDoc: (String) -> Unit) {
    val sources = d.note?.let { NoteLogic.sources(it.sources) }.orEmpty()
    if (sources.isEmpty()) {
        Text(
            "No sources yet. When notes are made from your Library, each fact lists the document and page it came from.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { s ->
            CcCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(NoteLogic.sourceLine(s), style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                    if (s.documentId.isNotBlank()) BigButton("Open the document", onClick = { onOpenDoc(s.documentId) }, filled = false)
                }
            }
        }
    }
}

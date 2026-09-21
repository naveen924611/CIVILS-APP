package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.data.model.TeluguItem
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.NotoSansTelugu
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel

/**
 * A translation sentence or a letter/essay task. The owner types the answer (Telugu keyboard), sends it, and the tutor's
 * feedback arrives as a job result (waiting for internet is fine: it is queued).
 */
@Composable
fun WritingScreen(item: TeluguItem, ui: TeluguUi, vm: TeluguViewModel, onClose: () -> Unit) {
    val isTranslation = item.kind == "translation"
    val translation = remember(item.id) { TeluguContent.translation(item.content) }
    val template = remember(item.id) { TeluguContent.template(item.content) }
    // the latest answer already sent for this item (so reopening it shows the feedback)
    val sent = ui.writing.firstOrNull { it.item.id == item.id }
    var text by remember(item.id) { mutableStateOf("") }
    var showReference by remember(item.id) { mutableStateOf(false) }
    var writingAgain by remember(item.id) { mutableStateOf(false) }
    val showSent = sent != null && !writingAgain
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Back", onClose, filled = false)
            Text(
                if (isTranslation) "Translation" else template.titleEn,
                style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink,
            )
        }
        if (isTranslation) TranslationTaskCard(translation, vm) else TemplateCard(template)
        if (showSent && sent != null) {
            CcCard(Modifier.fillMaxWidth()) {
                SectionLabel("Your answer")
                TeluguText(sent.progress.answer, size = 20.sp)
            }
            when (sent.state) {
                "done" -> sent.feedback?.let { FeedbackView(it) }
                "failed" -> CcCard(Modifier.fillMaxWidth()) {
                    Pill("Could not be checked", tone = 3)
                    Text(
                        sent.job?.error?.ifBlank { null } ?: "The tutor could not check this one. You can write it again.",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                    )
                }
                else -> CcCard(Modifier.fillMaxWidth()) {
                    Pill("Waiting for the tutor", tone = 2)
                    Text(
                        "Your answer is saved. The feedback comes when the internet is on, and you will get a message.",
                        style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                    )
                }
            }
            if (isTranslation) ReferenceCard(translation, showReference) { showReference = !showReference }
            BigButton("Write it again", { text = sent.progress.answer; writingAgain = true }, filled = false)
        } else {
            val words = TeluguContent.wordCount(text)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (isTranslation) 120.dp else 240.dp),
                textStyle = TextStyle(fontFamily = NotoSansTelugu, fontSize = 20.sp, lineHeight = 32.sp, color = Cc.colors.ink),
                label = { Text(if (isTranslation && !translation.toTelugu) "Your English translation" else "Write your answer in Telugu") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (isTranslation) "$words words" else "$words words (aim for about ${template.minWords})",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                )
            }
            BigButton(
                "Send for feedback",
                { vm.sendForFeedback(item, text); writingAgain = false },
                enabled = text.trim().length >= 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GeneralPracticeNote()
    }
}

@Composable
private fun TranslationTaskCard(t: TranslationTask, vm: TeluguViewModel) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel(if (t.toTelugu) "Translate into Telugu" else "Translate into English")
        if (t.toTelugu) {
            Text(t.en, style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        } else {
            TeluguText(t.te, size = 24.sp, bold = true)
            HearButton({ vm.speak(t.te) })
        }
        if (t.hints.isNotEmpty()) {
            Text("Helpful words", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            t.hints.forEach { h ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeluguText(h.te, size = 18.sp)
                    Text("= ${h.en}", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
            }
        }
    }
}

@Composable
private fun ReferenceCard(t: TranslationTask, shown: Boolean, toggle: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        BigButton(if (shown) "Hide the sample answer" else "Show a sample answer", toggle, filled = false)
        if (shown) {
            if (t.toTelugu) TeluguText(t.reference, size = 20.sp)
            else Text(t.reference, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
            Text(
                "There is often more than one right way to say it.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
    }
}

@Composable
private fun TemplateCard(t: WritingTemplate) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Your task")
        if (t.titleTe.isNotBlank()) TeluguText(t.titleTe, size = 22.sp, bold = true)
        Text(t.taskEn, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        if (t.structure.isNotEmpty()) {
            Text("How to lay it out", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            t.structure.forEachIndexed { i, s ->
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TeluguText("${i + 1}. ${s.te}", size = 18.sp)
                    Text(s.en, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
        if (t.phrases.isNotEmpty()) {
            Text("Useful phrases", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            t.phrases.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TeluguText(p.te, size = 18.sp)
                    Text(p.en, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
        if (t.sampleTe.isNotBlank()) {
            Text("A short model answer (try your own first)", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
            TeluguText(t.sampleTe, size = 18.sp)
        }
    }
}

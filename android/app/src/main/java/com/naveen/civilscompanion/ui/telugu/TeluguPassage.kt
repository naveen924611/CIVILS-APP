package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.data.model.TeluguItem
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill

/** A reading passage with multiple-choice questions. Checked on the tablet (no internet needed). */
@Composable
fun PassageScreen(item: TeluguItem, vm: TeluguViewModel, onClose: () -> Unit) {
    val passage = remember(item.id) { TeluguContent.passage(item.content) }
    val picked = remember(item.id) { mutableStateMapOf<Int, Int>() }
    var checked by remember(item.id) { mutableStateOf(false) }
    var showEnglish by remember(item.id) { mutableStateOf(false) }
    val correct = passage.questions.indices.count { picked[it] == passage.questions[it].answer }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Back", { vm.stopSpeaking(); onClose() }, filled = false)
            Text(passage.titleEn.ifBlank { "Reading" }, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        }
        CcCard(Modifier.fillMaxWidth()) {
            TeluguText(passage.title, size = 24.sp, bold = true)
            TeluguText(passage.textTe, size = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HearButton({ vm.speak(passage.textTe) }, label = "Hear the passage")
                if (checked) BigButton(if (showEnglish) "Hide English" else "Show English", { showEnglish = !showEnglish }, filled = false)
            }
            if (showEnglish) Text(passage.textEn, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
        passage.questions.forEachIndexed { qi, q ->
            CcCard(Modifier.fillMaxWidth()) {
                TeluguText("${qi + 1}. ${q.qTe}", size = 20.sp, bold = true)
                if (checked) Text(q.qEn, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                q.options.forEachIndexed { oi, option ->
                    OptionRow(
                        text = option,
                        selected = picked[qi] == oi,
                        state = when {
                            !checked -> 0
                            oi == q.answer -> 1
                            picked[qi] == oi -> 3
                            else -> 0
                        },
                        onClick = { if (!checked) picked[qi] = oi },
                    )
                }
            }
        }
        if (!checked) {
            BigButton(
                "Check my answers",
                {
                    checked = true
                    vm.record(item.id, TeluguPlan.passageScore(correct, passage.questions.size))
                },
                enabled = picked.size == passage.questions.size,
                modifier = Modifier.fillMaxWidth(),
            )
            if (picked.size < passage.questions.size) {
                Text("Answer every question first.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
        } else {
            CcCard(Modifier.fillMaxWidth()) {
                Text("You got $correct of ${passage.questions.size} right.", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
                GeneralPracticeNote()
                BigButton("Back to today", onClose)
            }
        }
    }
}

/** state: 0 normal, 1 the right answer (shown after checking), 3 the owner's wrong choice. */
@Composable
private fun OptionRow(text: String, selected: Boolean, state: Int, onClick: () -> Unit) {
    val c = Cc.colors
    val bg = when {
        state == 1 -> c.primaryTint
        state == 3 -> c.dangerTint
        selected -> c.accentTint
        else -> c.rail
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TeluguText(text, Modifier.weight(1f), size = 18.sp)
        if (state == 1) Pill("Right answer", tone = 1)
        if (state == 3) Pill("Your choice", tone = 3)
    }
}

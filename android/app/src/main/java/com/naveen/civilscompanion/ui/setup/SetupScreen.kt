package com.naveen.civilscompanion.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.exams.ExamSetupStep
import com.naveen.civilscompanion.ui.syllabus.SyllabusSetupStep

private val STEPS = listOf("Welcome", "Exams", "Syllabus", "Brief times", "This tablet", "Finish")

/**
 * First-run setup (spec 6.19): welcome, exams and study hours, syllabus, brief times, phone permissions and voice,
 * then the first plan. Login comes before this screen. Every step can be skipped; the chips at the top jump to a step,
 * and Settings can open this wizard again.
 */
@Composable
fun SetupScreen(onFinished: () -> Unit, vm: SetupViewModel = hiltViewModel()) {
    var step by remember { mutableIntStateOf(0) }
    val next = { step = (step + 1).coerceAtMost(STEPS.lastIndex) }
    val colors = Cc.colors

    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STEPS.forEachIndexed { i, label ->
                    FilterChip(
                        selected = i == step, onClick = { step = i }, label = { Text("${i + 1}. $label") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            when (step) {
                0 -> WelcomeStep(next)
                1 -> ExamSetupStep(onNext = next)
                2 -> SyllabusSetupStep(onNext = next)
                3 -> BriefTimesStep(vm) { next() }
                4 -> PermissionsStep(onNext = next)
                else -> FinishStep(vm, onFinished)
            }
            if (step > 0) {
                TextButton(onClick = { step = (step - 1).coerceAtLeast(0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Civils Companion", style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        Text(
            "This takes about five minutes. You will set your exams and study hours, approve your syllabus, choose when your " +
                "daily briefs arrive, and let the tablet send reminders and play audio with the screen off. " +
                "Everything can be changed later in Settings, and you can skip any step.",
            style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted,
        )
        BigButton("Start", onClick = onNext)
    }
}

@Composable
private fun BriefTimesStep(vm: SetupViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("When should your briefs arrive?", style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        Text(
            "The server prepares each brief 30 minutes before its time. You can add extra briefs later in Settings.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        s.slots.forEach { slot -> SlotRow(slot, vm) }
        s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onAccentTint) }
        BigButton("Save and continue", onClick = { vm.saveTimes(onDone) })
    }
}

@Composable
private fun SlotRow(slot: BriefSlotDto, vm: SetupViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(BriefTimes.label(slot.id), style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink, modifier = Modifier.widthIn(min = 150.dp))
        Switch(checked = slot.enabled, onCheckedChange = { vm.toggle(slot.id, it) })
        listOf("−1 h" to -60, "−15 m" to -15).forEach { (label, delta) ->
            OutlinedButton(onClick = { vm.shift(slot.id, delta) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
        Text(BriefTimes.display(slot.time), style = MaterialTheme.typography.headlineSmall, color = if (slot.enabled) Cc.colors.ink else Cc.colors.muted, modifier = Modifier.widthIn(min = 110.dp))
        listOf("+15 m" to 15, "+1 h" to 60).forEach { (label, delta) ->
            OutlinedButton(onClick = { vm.shift(slot.id, delta) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
    }
}

@Composable
private fun FinishStep(vm: SetupViewModel, onFinished: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("You are ready", style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        Text(
            "The app will now make your first plan from your exams, study hours and syllabus. " +
                "If you are offline, the plan appears after the next sync. Open Settings any time to change your choices.",
            style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted,
        )
        s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onAccentTint) }
        BigButton(if (s.planning) "Making your plan…" else "Make my first plan and open Today", onClick = { vm.finish(onFinished) }, enabled = !s.planning)
    }
}

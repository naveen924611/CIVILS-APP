package com.naveen.civilscompanion.ui.exams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.isCompact

/**
 * OWNER: Revision + Planner (M5). First-run setup step "Exams, dates, priority and study hours" (spec 6.19).
 * The Setup screen (owned by Settings) shows this composable in its own page; onNext is called when the owner presses
 * Continue. Everything is saved as it is changed (dates unknown are fine), so Continue only moves on.
 */
@Composable
fun ExamSetupStep(onNext: () -> Unit) {
    val vm: ExamsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.seedDefaults() }
    val compact = isCompact()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your exams and study hours", style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
        Text(
            "Add the exam dates you know. If a date is not announced yet, leave it empty. You can change all of this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.muted,
        )
        if (compact) {
            // Upright tablet: the cards go one under the other at full width.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExamListCard(ui.exams, vm)
                PriorityCard(ui.priorityMode, vm::setPriorityMode)
                HoursCard(ui, vm)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExamListCard(ui.exams, vm)
                    PriorityCard(ui.priorityMode, vm::setPriorityMode)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoursCard(ui, vm)
                }
            }
        }
        BigButton("Continue", onClick = onNext)
    }
}

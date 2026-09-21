package com.naveen.civilscompanion.ui.exams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.ExamDates
import com.naveen.civilscompanion.ui.today.PlanBlocks
import com.naveen.civilscompanion.ui.today.StudyPrefs
import java.time.LocalDate

/** Exams and study hours (spec 6.1 "editable dates", 6.8 Study plan): dates, priority, hours per weekday, Telugu minutes. */
@Composable
fun ExamsScreen(nav: NavHostController, vm: ExamsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val compact = isCompact()
    LaunchedEffect(Unit) { vm.seedSiOnce() }
    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 24.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            title = "Exams and study hours",
            subtitle = "Your exam dates set how the planner spreads your time.",
            actions = {
                OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("Back", color = Cc.colors.primary)
                }
            },
        )
        ExamListCard(ui.exams, vm)
        PriorityCard(ui.priorityMode, vm::setPriorityMode)
        SiGoalCard(nav)
        HoursCard(ui, vm)
    }
}

/** Exam rows with a date field each, plus "add" for a custom exam. */
@Composable
fun ExamListCard(exams: List<Exam>, vm: ExamsViewModel) {
    var newName by remember { mutableStateOf("") }
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Exams")
        if (exams.isEmpty()) {
            Text("No exams yet.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            BigButton("Add my exams", onClick = vm::seedDefaults, filled = false)
        }
        exams.forEach { ExamRow(it, vm) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Another exam (name)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                "Add",
                onClick = {
                    vm.addExam(newName, "Prelims")
                    newName = ""
                },
                filled = false,
                enabled = newName.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ExamRow(exam: Exam, vm: ExamsViewModel) {
    var text by remember(exam.id, exam.date) { mutableStateOf(ExamDates.parseDay(exam.date)?.toString() ?: "") }
    val invalid = text.isNotBlank() && parseDay(text) == null
    val today = remember { LocalDate.now(com.naveen.civilscompanion.ui.revise.ReviseData.INDIA) }
    val compact = isCompact()
    val dateField: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                val day = parseDay(it)
                if (day != null) vm.setDate(exam.id, day) else if (it.isBlank()) vm.setDate(exam.id, null)
            },
            label = { Text("Date (year-month-day)") },
            placeholder = { Text("2027-02-14") },
            isError = invalid,
            singleLine = true,
            modifier = fieldModifier,
        )
    }
    val tentative: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tentative", style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
            Switch(checked = exam.isTentative, onCheckedChange = { vm.setTentative(exam.id, it) })
        }
    }
    if (compact) {
        // Upright tablet: name on one line, the date field and buttons on the next.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text("${exam.name} · ${exam.stage}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(ExamDates.countdownText(exam.date, today), style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                dateField(Modifier.weight(1f))
                tentative()
                TextButton(onClick = { vm.deleteExam(exam.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${exam.name} · ${exam.stage}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            Text(ExamDates.countdownText(exam.date, today), style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        dateField(Modifier.width(230.dp))
        tentative()
        TextButton(onClick = { vm.deleteExam(exam.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
    }
}

private fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

/** All equal / more on UPSC / more on APPSC / more on SI (Civil). */
@Composable
fun PriorityCard(mode: Int, onMode: (Int) -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Which exam gets more time?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { onMode(0) }, label = { Text("Equal") })
            FilterChip(selected = mode == 1, onClick = { onMode(1) }, label = { Text("More on UPSC") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 2, onClick = { onMode(2) }, label = { Text("More on APPSC") })
            FilterChip(selected = mode == 3, onClick = { onMode(3) }, label = { Text("More on SI (Civil)") })
        }
        Text(
            "In the last 8 weeks before the nearer Prelims, the planner gives that exam about 70 percent of the time.",
            style = MaterialTheme.typography.bodySmall,
            color = Cc.colors.muted,
        )
    }
}

/** Link to the SI (Civil) goal screen: dates, eligibility, checklist, body check, running log. */
@Composable
fun SiGoalCard(nav: NavHostController) {
    CcCard(Modifier.fillMaxWidth(), onClick = { nav.navigate(Routes.GOALS) }) {
        SectionLabel("SI (Civil) goal")
        Text("SI (Civil) goal and checklist", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(
            "Your eligibility, certificates, body check, running log and cut-offs in one place.",
            style = MaterialTheme.typography.bodySmall,
            color = Cc.colors.muted,
        )
    }
}

/** Hours per weekday and Telugu minutes. */
@Composable
fun HoursCard(ui: ExamsUi, vm: ExamsViewModel) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Study hours each day")
        HoursEditor(ui.hours, vm::setHours)
        Text(
            "About ${PlanBlocks.hoursText(Math.round(StudyPrefs.weekHours(ui.hours) * 60).toInt())} a week. Sunday is lighter on new study by default.",
            style = MaterialTheme.typography.bodySmall,
            color = Cc.colors.muted,
        )
        Stepper(
            label = "Telugu practice (minutes a day)",
            valueText = "${ui.telugu}",
            onMinus = { vm.setTelugu(ui.telugu - 5) },
            onPlus = { vm.setTelugu(ui.telugu + 5) },
            canMinus = ui.telugu > 0,
        )
    }
}

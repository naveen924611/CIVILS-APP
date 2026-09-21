package com.naveen.civilscompanion.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import java.util.Locale

/** Card d: body measures (PMT) against the right standard, with pass or fail and the shortfall for each. */
@Composable
fun PmtCard(profile: GoalsData.SiProfile, pmt: GoalsData.PmtInput, onChange: ((GoalsData.PmtInput) -> GoalsData.PmtInput) -> Unit) {
    var height by remember { mutableStateOf(GoalsData.numberText(pmt.heightCm)) }
    var chest by remember { mutableStateOf(GoalsData.numberText(pmt.chestCm)) }
    var expanded by remember { mutableStateOf(GoalsData.numberText(pmt.chestExpandedCm)) }
    var weight by remember { mutableStateOf(GoalsData.numberText(pmt.weightKg)) }
    val result = SiRules.pmtCheck(profile.female, profile.aboSt, pmt.heightCm, pmt.chestCm, pmt.chestExpandedCm, pmt.weightKg)
    val who = (if (profile.female) "Women" else "Men") + (if (profile.aboSt) ", ABO-ST agency area" else "")

    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Body check (PMT)")
        MutedText("Standard used: $who. Change gender or ABO-ST in your profile above.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Height (cm)", height, { t ->
                height = t
                onChange { it.copy(heightCm = GoalsData.parseNumber(t)) }
            }, Modifier.weight(1f))
            if (profile.female) {
                NumberField("Weight (kg)", weight, { t ->
                    weight = t
                    onChange { it.copy(weightKg = GoalsData.parseNumber(t)) }
                }, Modifier.weight(1f))
            }
        }
        if (!profile.female) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("Chest (cm)", chest, { t ->
                    chest = t
                    onChange { it.copy(chestCm = GoalsData.parseNumber(t)) }
                }, Modifier.weight(1f))
                NumberField("Chest expanded (cm)", expanded, { t ->
                    expanded = t
                    onChange { it.copy(chestExpandedCm = GoalsData.parseNumber(t)) }
                }, Modifier.weight(1f))
            }
        }
        result.measures.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    when {
                        m.value == null -> "Not entered"
                        m.pass -> "Pass"
                        else -> "Short"
                    },
                    tone = when {
                        m.value == null -> 0
                        m.pass -> 1
                        else -> 3
                    },
                )
                Text(m.text(), style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            }
        }
        if (result.allPass) Text("All measures pass.", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
    }
}

/** Card e: the PET log. A form with an instant pass or fail, the best so far, and the past entries. */
@Composable
fun PetCard(
    profile: GoalsData.SiProfile,
    log: List<GoalsData.PetEntry>,
    onAdd: (GoalsData.PetEntry) -> Unit,
    onDelete: (String) -> Unit,
    newId: () -> String,
) {
    val standard = GoalsData.petProfile(profile)
    var date by remember { mutableStateOf(TimeUtil.today()) }
    var runText by remember { mutableStateOf("") }
    var sprintText by remember { mutableStateOf("") }
    var jumpText by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val preview = GoalsData.buildEntry("preview", date, runText, sprintText, jumpText).entry
    val best = GoalsData.bests(log)
    val shown = GoalsData.newestFirst(log).take(30)

    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("PET log")
        Text("Standard used: ${GoalsData.petProfileName(standard)}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        MutedText(GoalsData.petStandardText(standard) + ". Electronic timing, no spike shoes, no re-test.")
        OutlinedTextField(
            value = date,
            onValueChange = { date = it.take(10) },
            label = { Text("Date (year-month-day)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = runText,
            onValueChange = { runText = it.filter { c -> c.isDigit() || c == ':' || c == '.' }.take(8) },
            label = { Text("1600 m time (minutes:seconds, for example 8:05)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("100 m (seconds, optional)", sprintText, { sprintText = it }, Modifier.weight(1f))
            NumberField("Long jump (metres, optional)", jumpText, { jumpText = it }, Modifier.weight(1f))
        }
        if (preview != null) {
            PetResultLines(GoalsData.petResult(standard, preview), preview)
        } else {
            MutedText("Fill in the date and the 1600 m time to see pass or fail.")
        }
        formError?.let { message -> Pill(message, tone = 3) }
        BigButton(
            "Add to my log",
            onClick = {
                val parsed = GoalsData.buildEntry(newId(), date, runText, sprintText, jumpText)
                val entry = parsed.entry
                if (entry == null) {
                    formError = parsed.error
                } else {
                    formError = null
                    onAdd(entry)
                    runText = ""
                    sprintText = ""
                    jumpText = ""
                }
            },
            enabled = preview != null,
        )
        Text("Best so far", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        if (log.isEmpty()) {
            MutedText("No entries yet.")
        } else {
            Text(
                "1600 m: " + (best.run1600?.let { SiRules.formatRun(it) } ?: "none"),
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
            Text(
                "100 m: " + (best.sprint100?.let { SiRules.fmt(it) + " s" } ?: "none"),
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
            Text(
                "Long jump: " + (best.longJump?.let { String.format(Locale.ROOT, "%.2f", it) + " m" } ?: "none"),
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
        }
        shown.forEach { e ->
            val r = GoalsData.petResult(standard, e)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.date, style = MaterialTheme.typography.labelMedium, color = Cc.colors.muted)
                    Text(GoalsData.petSummary(e), style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                }
                Pill(if (r.passed) "Pass" else "Not yet", tone = if (r.passed) 1 else 3)
                TextButton(onClick = { onDelete(e.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete", color = Cc.colors.muted) }
            }
        }
        if (log.size > shown.size) MutedText("Showing the latest ${shown.size} of ${log.size} entries.")
    }
}

/** Pass or fail for each part and overall. */
@Composable
private fun PetResultLines(r: SiRules.PetResult, e: GoalsData.PetEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PartLine("1600 m", SiRules.formatRun(e.run1600), r.run1600Pass)
        PartLine("100 m", e.sprint100?.let { SiRules.fmt(it) + " s" } ?: "not entered", r.sprintPass)
        PartLine("Long jump", e.longJump?.let { String.format(Locale.ROOT, "%.2f", it) + " m" } ?: "not entered", r.longJumpPass)
        Pill(if (r.passed) "PET: pass" else "PET: not yet", tone = if (r.passed) 1 else 3)
        r.missing.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted) }
    }
}

@Composable
private fun PartLine(name: String, value: String, pass: Boolean?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(
            when (pass) {
                true -> "Pass"
                false -> "Not yet"
                null -> "-"
            },
            tone = when (pass) {
                true -> 1
                false -> 3
                null -> 0
            },
        )
        Text("$name: $value", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
    }
}

/** Card f: the Prelim cut-off for the owner's category and a small calculator for mock marks. */
@Composable
fun CutoffCard(profile: GoalsData.SiProfile) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val percent = SiRules.prelimCutoffPercent(profile.category)
    val m1 = GoalsData.parseMarks(p1)
    val m2 = GoalsData.parseMarks(p2)
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Prelim cut-off")
        Text(
            "Your category ${profile.category}: $percent percent in each paper, that is $percent marks out of 100 in Paper 1 and $percent out of 100 in Paper 2.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cc.colors.ink,
        )
        MutedText("OC and EWS 40, BC 35, SC and ST 30. You must reach it in BOTH papers. Prelim marks do not count in the final merit.")
        Text("Try your mock marks", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Paper 1 marks (of 100)", p1, { p1 = it }, Modifier.weight(1f))
            NumberField("Paper 2 marks (of 100)", p2, { p2 = it }, Modifier.weight(1f))
        }
        PaperLine("Paper 1 (Arithmetic and Reasoning)", m1, profile.category)
        PaperLine("Paper 2 (General Studies)", m2, profile.category)
        if (m1 != null && m2 != null) {
            val ok = SiRules.prelimPassed(profile.category, m1, m2)
            Pill(if (ok) "Prelim: qualified" else "Prelim: not qualified", tone = if (ok) 1 else 3)
        }
    }
}

@Composable
private fun PaperLine(name: String, marks: Double?, category: String) {
    if (marks == null) return
    val ok = SiRules.prelimPaperPassed(category, marks)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(if (ok) "Pass" else "Short", tone = if (ok) 1 else 3)
        Text(
            "$name: ${SiRules.fmt(marks)} (need ${SiRules.prelimCutoffPercent(category)})",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.ink,
        )
    }
}

/** Card g: what is not known yet. */
@Composable
fun UnknownCard() {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("What is not known yet")
        Text(
            "Age limits and physical rules for Group-I DSP come only with the Detailed Notification, due on or before 6 October 2026. " +
                "Do not rely on any number you have heard for Group-I until then.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.ink,
        )
        Text(
            "The Group-I exam dates and the SI application dates are not announced yet. Watch slprb.ap.gov.in for the SI press release " +
                "and psc.ap.gov.in for Group-I.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.ink,
        )
    }
}

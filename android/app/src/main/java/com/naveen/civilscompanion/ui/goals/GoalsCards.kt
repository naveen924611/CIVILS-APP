package com.naveen.civilscompanion.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.exams.Stepper
import java.time.LocalDate

/** Card a: the dates that matter and an "I applied" tick for each application. */
@Composable
fun DatesCard(applied: Map<String, Boolean>, today: LocalDate, onApplied: (key: String, value: Boolean) -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Dates")
        Text("APPSC Group-I: application window", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(
            "6 October 2026 to 27 October 2026 (closes at 11:59 PM)",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.ink,
        )
        Pill(GoalsData.windowText(GoalsData.GROUP1_OPENS, GoalsData.GROUP1_CLOSES, today), tone = 2)
        CheckRow(
            "I applied for Group-I",
            applied[GoalsData.APPLIED_GROUP1] == true,
            { onApplied(GoalsData.APPLIED_GROUP1, it) },
            source = "Notification: APPSC Brief Notification 07/2026",
        )
        Text("Group-I Detailed Notification", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text("Due on or before 6 October 2026 (age limits, physical rules, vacancies)", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        Pill(GoalsData.dueText(GoalsData.DETAILED_NOTIFICATION_DUE, today), tone = 2)
        Text("SI (Civil): application dates", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        Text(
            "Not announced (press release on slprb.ap.gov.in)",
            style = MaterialTheme.typography.bodyMedium,
            color = Cc.colors.ink,
        )
        CheckRow(
            "I applied for SI (Civil)",
            applied[GoalsData.APPLIED_SI] == true,
            { onApplied(GoalsData.APPLIED_SI, it) },
            source = "Notification: SLPRB Rc.No.81/SLPRB/Rect.1/2026",
        )
    }
}

/** Card b: the owner's SI profile and the age result. */
@Composable
fun ProfileCard(profile: GoalsData.SiProfile, onChange: ((GoalsData.SiProfile) -> GoalsData.SiProfile) -> Unit) {
    var day by remember { mutableStateOf(profile.dob?.dayOfMonth?.toString() ?: "") }
    var month by remember { mutableStateOf(profile.dob?.monthValue?.toString() ?: "") }
    var year by remember { mutableStateOf(profile.dob?.year?.toString() ?: "") }
    val typedDate = GoalsData.dobFromText(day, month, year)
    val anyTyped = day.isNotBlank() || month.isNotBlank() || year.isNotBlank()
    val age = GoalsData.ageResult(profile)

    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("My SI profile")
        MutedText("Saved on this tablet and synced later. It is only used for the checks below.")
        Text("Gender", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
        ChoiceChips(
            listOf("male" to "Male", "female" to "Female"),
            if (profile.female) "female" else "male",
        ) { pick -> onChange { it.copy(female = pick == "female") } }
        Text("Category", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
        ChoiceChips(SiRules.CATEGORIES.map { it to it }, profile.category) { pick -> onChange { it.copy(category = pick) } }
        Text("Date of birth (as on your SSC certificate)", style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Day", day, { text ->
                day = text
                GoalsData.dobFromText(text, month, year)?.let { d -> onChange { it.copy(dob = d) } }
            }, Modifier.weight(1f))
            NumberField("Month", month, { text ->
                month = text
                GoalsData.dobFromText(day, text, year)?.let { d -> onChange { it.copy(dob = d) } }
            }, Modifier.weight(1f))
            NumberField("Year", year, { text ->
                year = text
                GoalsData.dobFromText(day, month, text)?.let { d -> onChange { it.copy(dob = d) } }
            }, Modifier.weight(1.4f))
        }
        if (anyTyped && typedDate == null) MutedText("Write a real date, for example day 9, month 3, year 2001.")
        CheckRow("I am a local candidate", profile.local, { v -> onChange { it.copy(local = v) } }, detail = "95 percent of the posts are for local candidates.")
        CheckRow("I am an ex-serviceman", profile.exServiceman, { v -> onChange { it.copy(exServiceman = v) } })
        CheckRow("I work for the AP State Government", profile.govtEmployee, { v -> onChange { it.copy(govtEmployee = v) } }, detail = "APTRANSCO, DISCOMs, APGENCO, corporations and local bodies do not count.")
        CheckRow("I am an NCC whole-time instructor", profile.nccInstructor, { v -> onChange { it.copy(nccInstructor = v) } })
        if (profile.exServiceman || profile.govtEmployee || profile.nccInstructor) {
            Stepper(
                label = "Years of service",
                valueText = "${profile.serviceYears}",
                onMinus = { onChange { it.copy(serviceYears = (it.serviceYears - 1).coerceAtLeast(0)) } },
                onPlus = { onChange { it.copy(serviceYears = (it.serviceYears + 1).coerceAtMost(40)) } },
                canMinus = profile.serviceYears > 0,
                canPlus = profile.serviceYears < 40,
            )
        }
        CheckRow(
            "I am an ABO-ST candidate from an agency area",
            profile.aboSt,
            { v -> onChange { it.copy(aboSt = v) } },
            detail = "Agency-area candidates have lower body measures.",
        )
        CheckRow("My degree is completed", profile.degreeDone, { v -> onChange { it.copy(degreeDone = v) } }, detail = "Any degree, completed on or before 1 July 2026.")
        Text("Age check", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        if (age == null) {
            MutedText("Enter your date of birth to check your age.")
        } else {
            Pill(if (age.eligible) "Age: eligible" else "Age: not eligible", tone = if (age.eligible) 1 else 3)
            Text(age.reason, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        }
    }
}

/** Card c: the checklist of things to have ready. */
@Composable
fun ChecklistCard(profile: GoalsData.SiProfile, checked: Map<String, Boolean>, onCheck: (key: String, value: Boolean) -> Unit) {
    val fee = SiRules.feeFor(profile.category, profile.local)
    val items = GoalsData.checklistItems(fee)
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Checklist")
        Text(
            "${GoalsData.checklistDone(items, checked)} of ${items.size} ready",
            style = MaterialTheme.typography.titleMedium,
            color = Cc.colors.ink,
        )
        items.forEach { item ->
            CheckRow(
                label = item.label,
                checked = checked[item.key] == true,
                onChange = { v -> onCheck(item.key, v) },
                detail = item.detail,
                source = item.source,
            )
        }
        MutedText("Only ticks are stored here. Never type passwords or payment details in this app.")
    }
}

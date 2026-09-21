package com.naveen.civilscompanion.ui.revise

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.exams.Stepper
import com.naveen.civilscompanion.ui.nav.Routes

/** My schedule rules (spec 6.15): revision time, cards a day, Sunday review, subject first, daily current affairs. */
@Composable
fun ReviseRulesScreen(nav: NavHostController, vm: ReviseViewModel = hiltViewModel()) {
    val compact = isCompact()
    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 24.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            title = "Revision rules",
            subtitle = "These are your own rules. The app follows them every day.",
            actions = {
                OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("Back", color = Cc.colors.primary)
                }
            },
        )
        RulesForm(vm, full = true)
    }
}

/**
 * The rule controls. full = false shows only the basics (used inside Settings > Study plan), full = true adds
 * "subject always first" and the daily current-affairs rule.
 */
@Composable
fun RulesForm(vm: ReviseViewModel, full: Boolean) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val q by vm.queue.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    var slotText by remember(s.slotTime) { mutableStateOf(s.slotTime) }

    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Time and size")
        OutlinedTextField(
            value = slotText,
            onValueChange = {
                slotText = it
                vm.setSlot(it)
            },
            label = { Text("Revision time (24-hour, for example 18:00)") },
            isError = !ReviseData.isValidTime(slotText),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val max = q.config.maxCards
        Stepper(
            label = "Most cards a day",
            valueText = "$max",
            onMinus = { vm.setMaxCards(max - 10) },
            onPlus = { vm.setMaxCards(max + 10) },
            canMinus = max > 10,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sunday: review the whole week", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            Switch(checked = s.sundayReview, onCheckedChange = { vm.setSundayReview(it) })
        }
    }
    if (!full) return

    val pinned = rules.firstOrNull { it.type == "pinned_subject" && it.enabled }
        ?.params?.get("subject")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
    val subjects = q.topics.values.map { it.subject }.filter { it.isNotBlank() }.distinct().sorted()
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Subject always first")
        Text("Cards of this subject come first every day.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = pinned.isEmpty(), onClick = { vm.setPinnedSubject("") }, label = { Text("None") })
            subjects.forEach { name ->
                FilterChip(selected = pinned == name, onClick = { vm.setPinnedSubject(name) }, label = { Text(name) })
            }
        }
    }
    val dailyOn = q.config.dailyGroup != null
    val dailyCards = q.config.dailyGroupCards
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Daily current affairs")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Always include current affairs cards", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            Switch(checked = dailyOn, onCheckedChange = { vm.setDailyGroup(it, dailyCards) })
        }
        if (dailyOn) {
            Stepper(
                label = "Cards every day",
                valueText = "$dailyCards",
                onMinus = { vm.setDailyGroup(true, dailyCards - 5) },
                onPlus = { vm.setDailyGroup(true, dailyCards + 5) },
                canMinus = dailyCards > 5,
            )
        }
    }
}

/** Settings > Study plan: revision time, cards a day, Sunday review, and a link to all the rules. */
@Composable
fun ReviseSettingsBlock(nav: NavHostController, vm: ReviseViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RulesForm(vm, full = false)
        OutlinedButton(
            onClick = { runCatching { nav.navigate(Routes.REVISE_RULES) } },
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("More revision rules", color = Cc.colors.primary) }
    }
}

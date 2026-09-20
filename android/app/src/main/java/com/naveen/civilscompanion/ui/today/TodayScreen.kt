package com.naveen.civilscompanion.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes

/** Opens the screen a plan block points to (its ref, or the topic's notes). Unknown screens are ignored. */
internal fun startBlock(nav: NavHostController, block: PlanBlock) {
    val route = block.ref ?: block.topicId?.let { Routes.noteTopic(it) } ?: return
    runCatching { nav.navigate(route) }
}

/** Today (spec 6.1): greeting, plan checklist with Start buttons, status chips, and the right column. */
@Composable
fun TodayScreen(nav: NavHostController, vm: TodayViewModel = hiltViewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background).padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(s, vm, nav)
            Chips(s)
            s.message?.let { MessageBar(it, vm::dismissMessage) }
            SectionLabel("Today's plan")
            if (s.loaded && s.blocks.isEmpty()) {
                CcCard(Modifier.fillMaxWidth()) {
                    Text("Rest day: nothing is planned.", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                    Text(
                        "You can change the hours for each day in Settings, under Study plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cc.colors.muted,
                    )
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.blocks, key = { it.block.id }) { b ->
                    BlockRow(b, onStart = { startBlock(nav, b.block) }, onToggle = { vm.setDone(b, !b.done) })
                }
            }
        }
        RightColumn(s, nav, Modifier.width(340.dp).fillMaxHeight())
    }
}

@Composable
private fun Header(s: TodayUi, vm: TodayViewModel, nav: NavHostController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${s.greeting}, Naveen", style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
            Text(
                listOf(s.longDate, s.hoursText).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Cc.colors.muted,
            )
            if (s.summary.isNotBlank()) Text(s.summary, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        OutlinedButton(onClick = { vm.replan() }, enabled = !s.busy, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
            Text(if (s.busy) "Planning..." else "Update my plan", color = Cc.colors.primary)
        }
        OutlinedButton(onClick = { runCatching { nav.navigate(Routes.PLANNER) } }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
            Text("Week view", color = Cc.colors.primary)
        }
    }
}

@Composable
private fun Chips(s: TodayUi) {
    if (!s.offline && s.waiting == 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.offline) Pill("Offline · using saved content", tone = 2)
        if (s.waiting > 0) Pill(if (s.waiting == 1) "1 question waiting for internet" else "${s.waiting} questions waiting for internet", tone = 2)
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    CcCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
        }
    }
}

@Composable
private fun BlockRow(b: TodayBlockUi, onStart: () -> Unit, onToggle: () -> Unit) {
    val block = b.block
    CcCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                PlanBlocks.displayTime(block.start),
                style = MaterialTheme.typography.labelLarge,
                color = Cc.colors.muted,
                modifier = Modifier.width(84.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(block.title, style = MaterialTheme.typography.titleMedium, color = if (b.done) Cc.colors.muted else Cc.colors.ink)
                if (block.detail.isNotBlank()) Text(block.detail, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
            Pill("${block.minutes} min")
            if (b.done) {
                Pill("Done", tone = 1)
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
            } else {
                BigButton(PlanBlocks.startLabel(block.kind), onClick = onStart)
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mark done") }
            }
        }
    }
}

@Composable
private fun RightColumn(s: TodayUi, nav: NavHostController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.cont?.let { c ->
            CcCard(Modifier.fillMaxWidth(), onClick = { runCatching { nav.navigate(Routes.readDoc(c.docId)) } }) {
                SectionLabel("Continue reading")
                Text(c.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
        }
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Exam countdown")
            if (s.exams.isEmpty()) {
                Text("Add your exam dates to see the countdown.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            }
            s.exams.forEach { e ->
                Column {
                    Text(e.label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    Text(e.text, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
            OutlinedButton(
                onClick = { runCatching { nav.navigate(Routes.EXAMS) } },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Edit dates", color = Cc.colors.primary) }
        }
        CcCard(Modifier.fillMaxWidth(), onClick = { runCatching { nav.navigate(Routes.REPORT) } }) {
            SectionLabel("This week")
            Text(
                "${s.week.blocksDone} of ${s.week.blocksPlanned} blocks done",
                style = MaterialTheme.typography.titleMedium,
                color = Cc.colors.ink,
            )
            Text("${s.week.cardsRevised} cards revised", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            if (s.week.hoursText.isNotBlank()) {
                Text("${s.week.hoursText} planned in a normal week", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
        }
    }
}

package com.naveen.civilscompanion.ui.revise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.srs.QueueGroup
import com.naveen.civilscompanion.srs.RevisionQueue
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.PlanBlocks

private fun navigateTo(nav: NavHostController, route: String) {
    runCatching { nav.navigate(route) }
}

/** Revise hub (spec 6.15): today's queue with reasons, smart / my order, snooze, and the schedule rules. */
@Composable
fun ReviseScreen(nav: NavHostController, vm: ReviseViewModel = hiltViewModel()) {
    val q by vm.queue.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            title = "Revise",
            subtitle = if (q.totalCards == 0) "Nothing due right now" else "${q.totalCards} cards · about ${q.minutes} min",
        )
        RevTabs(nav)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            QueueList(q, vm, Modifier.weight(1f))
            SidePanel(q, settings, nav, vm, Modifier.width(340.dp))
        }
    }
}

@Composable
private fun RevTabs(nav: NavHostController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TabLabel("Today's queue", selected = true) {}
        TabLabel("Mock tests", selected = false) { navigateTo(nav, Routes.TESTS) }
        TabLabel("Explain it back", selected = false) { navigateTo(nav, Routes.EXPLAIN) }
        TabLabel("Revision sheets", selected = false) { navigateTo(nav, Routes.SHEETS) }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Cc.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.onPrimaryTint else colors.muted,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) colors.primaryTint else colors.rail, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun QueueList(q: QueueState, vm: ReviseViewModel, modifier: Modifier) {
    if (q.groups.isEmpty()) {
        CcCard(modifier.fillMaxWidth()) {
            Text(
                if (q.loaded) "You are all caught up." else "Loading your cards...",
                style = MaterialTheme.typography.titleMedium,
                color = Cc.colors.ink,
            )
            Text(
                "Cards come from your notes (Must remember points) and from the daily briefs. New ones appear here as soon as they are due.",
                style = MaterialTheme.typography.bodyMedium,
                color = Cc.colors.muted,
            )
        }
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(q.groups, key = { _, g -> g.key }) { index, g ->
            GroupRow(index, g, isFirst = index == 0, isLast = index == q.groups.lastIndex, vm = vm, perCard = q.config.minutesPerCard)
        }
    }
}

private fun toneOf(reason: String): Int = when (reason) {
    RevisionQueue.REASON_WEAK -> 3
    RevisionQueue.REASON_FADING, RevisionQueue.REASON_PAPERS -> 2
    RevisionQueue.REASON_PINNED, RevisionQueue.REASON_SUNDAY -> 1
    else -> 0
}

@Composable
private fun GroupRow(index: Int, g: QueueGroup, isFirst: Boolean, isLast: Boolean, vm: ReviseViewModel, perCard: Double) {
    CcCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.muted, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(g.reason, tone = toneOf(g.reason))
                    Text("${g.count} cards · ${g.minutes(perCard)} min", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
            TextButton(onClick = { vm.snooze(g) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Snooze") }
            OutlinedButton(
                onClick = { vm.move(g.key, -1) },
                enabled = !isFirst,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Move ${g.title} up" },
                shape = MaterialTheme.shapes.small,
            ) { Text("↑", color = Cc.colors.primary) }
            OutlinedButton(
                onClick = { vm.move(g.key, 1) },
                enabled = !isLast,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Move ${g.title} down" },
                shape = MaterialTheme.shapes.small,
            ) { Text("↓", color = Cc.colors.primary) }
        }
    }
}

@Composable
private fun SidePanel(q: QueueState, s: RevisionSettings, nav: NavHostController, vm: ReviseViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Today")
            Text(
                if (q.isSunday) "Sunday: full-week review is on." else "Your queue is ordered by what you are most likely to forget.",
                style = MaterialTheme.typography.bodyMedium,
                color = Cc.colors.muted,
            )
            BigButton(
                text = if (q.totalCards == 0) "Start revision" else "Start revision · ${q.minutes} min",
                onClick = { navigateTo(nav, Routes.REVISE_SESSION) },
                enabled = q.totalCards > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Order")
            if (q.mode == "my") {
                Pill("My order for today", tone = 1)
                OutlinedButton(onClick = vm::smartOrder, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("Back to Smart order", color = Cc.colors.primary)
                }
            } else {
                Pill("Smart order", tone = 1)
                Text("Use the arrows to make your own order for today.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
        }
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("My schedule rules")
            Text("Revision time: ${PlanBlocks.displayTime(s.slotTime)}", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            Text("At most ${q.config.maxCards} cards a day", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            Text(
                if (s.sundayReview) "Sunday full-week review: on" else "Sunday full-week review: off",
                style = MaterialTheme.typography.bodyMedium,
                color = Cc.colors.ink,
            )
            q.config.pinnedSubject?.let { Text("$it always first", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink) }
            q.config.dailyGroup?.let {
                Text("$it: ${q.config.dailyGroupCards} cards every day", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            }
            OutlinedButton(
                onClick = { navigateTo(nav, Routes.REVISE_RULES) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Change rules", color = Cc.colors.primary) }
        }
    }
}

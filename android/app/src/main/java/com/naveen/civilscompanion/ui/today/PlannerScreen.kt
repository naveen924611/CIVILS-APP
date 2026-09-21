package com.naveen.civilscompanion.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** Planner (spec 7.6): the next seven days, block by block. Tap a block to open it. */
@Composable
fun PlannerScreen(nav: NavHostController, vm: PlannerViewModel = hiltViewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val compact = isCompact()
    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background)
            .padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (compact) {
            ScreenTitle(
                title = "Planner",
                subtitle = "Your next seven days. Sunday is lighter on new study: review and a mock test.",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlannerButtons(s.busy, vm, nav)
            }
        } else {
            ScreenTitle(
                title = "Planner",
                subtitle = "Your next seven days. Sunday is lighter on new study: review and a mock test.",
                actions = { PlannerButtons(s.busy, vm, nav) },
            )
        }
        s.message?.let { msg ->
            CcCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
            }
        }
        LazyRow(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(s.days, key = { it.date }) { day -> DayCard(day, nav, if (compact) 300.dp else 250.dp) }
        }
    }
}

@Composable
private fun RowScope.PlannerButtons(busy: Boolean, vm: PlannerViewModel, nav: NavHostController) {
    OutlinedButton(onClick = { vm.replan() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
        Text(if (busy) "Planning..." else "Plan the week again", color = Cc.colors.primary)
    }
    OutlinedButton(onClick = { runCatching { nav.navigate(Routes.EXAMS) } }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
        Text("Exams and hours", color = Cc.colors.primary)
    }
    OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
        Text("Back", color = Cc.colors.primary)
    }
}

@Composable
private fun DayCard(day: DayUi, nav: NavHostController, cardWidth: Dp) {
    CcCard(Modifier.width(cardWidth).fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(day.label, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            if (day.isToday) Pill("Today", tone = 1)
        }
        if (!day.hasPlan) {
            Text("Not planned yet. Tap \"Plan the week again\" when you are online.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            return@CcCard
        }
        Text(day.hoursText, style = MaterialTheme.typography.labelLarge, color = Cc.colors.muted)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (day.blocks.isEmpty()) Text("Rest day", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            day.blocks.forEach { b -> BlockLine(b, onClick = { startBlock(nav, b.block) }) }
        }
    }
}

@Composable
private fun BlockLine(b: TodayBlockUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${PlanBlocks.displayTime(b.block.start)} · ${b.block.minutes} min" + if (b.done) " · done" else "",
            style = MaterialTheme.typography.labelSmall,
            color = Cc.colors.muted,
        )
        Text(b.block.title, style = MaterialTheme.typography.bodyMedium, color = if (b.done) Cc.colors.muted else Cc.colors.ink)
    }
}

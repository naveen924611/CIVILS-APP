package com.naveen.civilscompanion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.ask.AskSettingsSection
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.library.LibrarySettingsSection
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.revise.ReviseSettingsSection
import com.naveen.civilscompanion.ui.telugu.TeluguSettingsSection
import com.naveen.civilscompanion.ui.tests.TestSettingsSection
import com.naveen.civilscompanion.ui.today.PlannerSettingsSection

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * Settings (spec 6.8): colours and text size, daily briefs, downloads, voice, study plan (every feature's own section),
 * notifications, storage, AI usage, tablet setup and log out.
 */
@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onRunSetup: () -> Unit,
    nav: androidx.navigation.NavHostController,
    vm: SettingsViewModel = hiltViewModel(),
    storageVm: StorageViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val colors = Cc.colors
    val compact = isCompact()
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 20.dp else 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = colors.ink)

            SettingsHeading("Colours and text size")
            AppearanceSection(s, vm)

            Text("Daily briefs", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Text(
                "The server prepares each brief 30 minutes before its time, so it is ready when you are.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            s.slots.forEach { slot ->
                SlotCard(
                    slot,
                    onEnabled = { vm.setEnabled(slot.id, it) },
                    onShift = { vm.shiftTime(slot.id, it) },
                    onDay = { vm.toggleDay(slot.id, it) },
                    onRemove = { vm.removeExtra(slot.id) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.slots.size < 4) {
                    OutlinedButton(
                        onClick = vm::addExtra,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Add an extra brief") }
                }
                Button(
                    onClick = vm::save,
                    enabled = s.dirty && !s.saving,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (s.saving) "Saving…" else "Save brief times") }
            }
            s.message?.let {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.accentTint).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, color = colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
            }

            Text("Downloads", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Download brief audio on Wi-Fi only",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = s.wifiOnly, onCheckedChange = vm::setWifiOnly)
            }

            SettingsHeading("Voice and reading")
            AskSettingsSection(nav)

            SettingsHeading("Study plan")
            PlannerSettingsSection(nav)
            ReviseSettingsSection(nav)
            LibrarySettingsSection(nav)
            TestSettingsSection(nav)
            TeluguSettingsSection(nav)

            SettingsHeading("Notifications")
            NotificationsSection(s, vm)

            SettingsHeading("Storage")
            StorageSection(storageVm, showBackups = false)
            OutlinedButton(
                onClick = { runCatching { nav.navigate(Routes.STORAGE) } },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Backups and more storage details") }

            SettingsHeading("AI usage")
            AiUsageSection(storageVm)

            Text("Tablet setup", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            if (!s.exactAlarms) {
                Text(
                    "Exact alarms are off, so brief reminders can be a few minutes late. Open the setup to allow them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            OutlinedButton(
                onClick = onRunSetup,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Check notifications, battery and voice") }

            Text("Account", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            OutlinedButton(
                onClick = onLogout,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Log out") }
        }
    }
}

@Composable
private fun SlotCard(
    slot: BriefSlotDto,
    onEnabled: (Boolean) -> Unit,
    onShift: (Int) -> Unit,
    onDay: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.border, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                BriefTimes.label(slot.id),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (slot.id != "morning" && slot.id != "evening") {
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            }
            Switch(checked = slot.enabled, onCheckedChange = onEnabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−1 h") { onShift(-60) }
            StepButton("−5 m") { onShift(-5) }
            Text(
                BriefTimes.display(slot.time),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (slot.enabled) colors.ink else colors.muted,
                modifier = Modifier.widthIn(min = 120.dp),
            )
            StepButton("+5 m") { onShift(5) }
            StepButton("+1 h") { onShift(60) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_LETTERS.forEachIndexed { day, letter ->
                FilterChip(
                    selected = day in slot.days,
                    onClick = { onDay(day) },
                    label = { Text(letter) },
                    modifier = Modifier.heightIn(min = 48.dp).semanticsDescription(DAY_NAMES[day]),
                )
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}

private fun Modifier.semanticsDescription(text: String): Modifier =
    this.then(Modifier.semantics { contentDescription = text })

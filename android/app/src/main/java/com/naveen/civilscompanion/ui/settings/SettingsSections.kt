package com.naveen.civilscompanion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.notify.DayNotifyLogic
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.SectionLabel
import kotlin.math.roundToInt

@Composable
fun SettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SmallStep(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}

@Composable
private fun TimeStepper(time: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallStep("−1 h") { onChange(DayNotifyLogic.shift(time, -60)) }
        SmallStep("−15 m") { onChange(DayNotifyLogic.shift(time, -15)) }
        Text(
            DayNotifyLogic.display(time), style = MaterialTheme.typography.titleLarge, color = Cc.colors.ink,
            modifier = Modifier.widthIn(min = 100.dp),
        )
        SmallStep("+15 m") { onChange(DayNotifyLogic.shift(time, 15)) }
        SmallStep("+1 h") { onChange(DayNotifyLogic.shift(time, 60)) }
    }
}

/** Theme (system, light, dark) and text size. Saved as the settings ui.theme and ui.text_scale. */
@Composable
fun AppearanceSection(s: SettingsUi, vm: SettingsViewModel) {
    var scale by remember(s.textScale) { mutableFloatStateOf(s.textScale) }
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Colours")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Like the tablet", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
                FilterChip(
                    selected = s.theme == id, onClick = { vm.setTheme(id) }, label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        SectionLabel("Text size")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            Slider(
                value = scale,
                onValueChange = { scale = (it * 10f).roundToInt() / 10f },
                valueRange = 0.8f..1.6f,
                steps = 7,
                onValueChangeFinished = { vm.setTextScale(scale) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            Text("A", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.muted)
        }
        Text(
            "Size ${(scale * 100).roundToInt()}%. The change applies when you let go of the slider.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
    }
}

/** Revision reminder, evening day summary, weekly report and quiet hours (spec section 11). */
@Composable
fun NotificationsSection(s: SettingsUi, vm: SettingsViewModel) {
    CcCard(Modifier.fillMaxWidth()) {
        SwitchLine("Revision reminder at your revision time", s.notifyRevision, vm::setNotifyRevision)
        Text(
            "The time is set under Revision. The message says how many cards are due.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
        SwitchLine("Evening day summary", s.summary.enabled) { vm.setSummary(s.summary.copy(enabled = it)) }
        if (s.summary.enabled) TimeStepper(s.summary.time) { vm.setSummary(s.summary.copy(time = it)) }
        SwitchLine("Weekly report ready (Sunday evening)", s.notifyWeekly, vm::setNotifyWeekly)
        SwitchLine("Quiet hours: no reminders at night", s.quiet.enabled) { vm.setQuiet(s.quiet.copy(enabled = it)) }
        if (s.quiet.enabled) {
            Text("From", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            TimeStepper(s.quiet.start) { vm.setQuiet(s.quiet.copy(start = it)) }
            Text("Until", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            TimeStepper(s.quiet.end) { vm.setQuiet(s.quiet.copy(end = it)) }
            Text(
                "Your brief times are not affected: a brief you scheduled yourself still arrives.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
    }
}

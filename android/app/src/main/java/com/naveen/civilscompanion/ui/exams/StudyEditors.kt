package com.naveen.civilscompanion.ui.exams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.today.StudyPrefs
import java.util.Locale

/** A round minus / plus button, 48 dp. */
@Composable
fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge, color = Cc.colors.primary) }
}

/** "Label   [-] value [+]" on one line. */
@Composable
fun Stepper(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    canMinus: Boolean = true,
    canPlus: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
        StepButton("−", "Less $label", canMinus, onMinus)
        Text(valueText, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        StepButton("+", "More $label", canPlus, onPlus)
    }
}

/** Hours of study for each weekday: seven small columns with + and − (half an hour a step). */
@Composable
fun HoursEditor(hours: Map<String, Double>, onChange: (day: String, hours: Double) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StudyPrefs.DAY_KEYS.forEachIndexed { i, key ->
            val value = hours[key] ?: StudyPrefs.DEFAULT_HOURS.getValue(key)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(StudyPrefs.DAY_LABELS[i], style = MaterialTheme.typography.labelMedium, color = Cc.colors.muted)
                StepButton("+", "More hours on ${StudyPrefs.DAY_LABELS[i]}", value < StudyPrefs.MAX_HOURS) { onChange(key, Math.min(value + 0.5, StudyPrefs.MAX_HOURS)) }
                Text(hoursLabel(value), style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                StepButton("−", "Fewer hours on ${StudyPrefs.DAY_LABELS[i]}", value > 0.0) { onChange(key, Math.max(value - 0.5, 0.0)) }
            }
        }
    }
}

/** 4.0 -> "4 h", 3.5 -> "3.5 h", 0 -> "Off". */
fun hoursLabel(h: Double): String = when {
    h <= 0.0 -> "Off"
    h % 1.0 == 0.0 -> "${h.toInt()} h"
    else -> String.format(Locale.ROOT, "%.1f h", h)
}

package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc

private val DAYS = listOf("mon" to "Mon", "tue" to "Tue", "wed" to "Wed", "thu" to "Thu", "fri" to "Fri", "sat" to "Sat", "sun" to "Sun")

/** OWNER: Tests (M8). Shown inside Settings under "Study plan": negative marking default, weekly mock day. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TestSettingsSection(nav: NavHostController) {
    val vm: TestSettingsViewModel = hiltViewModel()
    val negative by vm.negative.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mock tests", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = negative, onCheckedChange = vm::setNegative)
            Column(Modifier.weight(1f)) {
                Text("Negative marking", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
                Text(
                    "New tests lose one third of a mark for each wrong answer. You can still change it before you start a test.",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                )
            }
        }
        Text("Weekly mock test day", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAYS.forEach { (key, label) -> TChip(label, selected = day == key, onClick = { vm.setDay(key) }) }
        }
        Text("The test is made the evening before (25 questions, 30 minutes).", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
}

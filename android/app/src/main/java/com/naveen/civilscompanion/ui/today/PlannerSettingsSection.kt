package com.naveen.civilscompanion.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.exams.ExamsViewModel
import com.naveen.civilscompanion.ui.exams.HoursCard
import com.naveen.civilscompanion.ui.nav.Routes

/** Shown inside Settings under "Study plan": hours per weekday, Telugu minutes, and a link to exam dates and priority. */
@Composable
fun PlannerSettingsSection(nav: NavHostController) {
    val vm: ExamsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Study plan", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        HoursCard(ui, vm)
        OutlinedButton(
            onClick = { runCatching { nav.navigate(Routes.EXAMS) } },
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("Exam dates and priority", color = Cc.colors.primary) }
        Text(
            "The library day is set under Library settings. It never blocks your plan.",
            style = MaterialTheme.typography.bodySmall,
            color = Cc.colors.muted,
        )
    }
}

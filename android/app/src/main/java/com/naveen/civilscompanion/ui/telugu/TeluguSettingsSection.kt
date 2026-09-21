package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Reads and writes the shared setting `study.telugu_minutes` (the planner reserves this time every day). */
@HiltViewModel
class TeluguSettingsViewModel @Inject constructor(private val kv: KvRepository) : ViewModel() {
    val minutes: StateFlow<Int> = kv.observe(TeluguPlan.KEY_MINUTES)
        .map { TeluguPlan.parseMinutes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeluguPlan.DEFAULT_MINUTES)

    fun setMinutes(value: Int) {
        val v = value.coerceIn(0, 120)
        viewModelScope.launch { kv.put(TeluguPlan.KEY_MINUTES, JsonPrimitive(v)) }
    }
}

/** Shown inside Settings under "Study plan": daily Telugu minutes, and links to Telugu practice and the monthly digests. */
@Composable
fun TeluguSettingsSection(nav: NavHostController) {
    val vm: TeluguSettingsViewModel = hiltViewModel()
    val minutes by vm.minutes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Telugu practice", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Minutes a day", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { vm.setMinutes(minutes - 5) },
                enabled = minutes > 0,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("-5", color = Cc.colors.primary) }
            Text("$minutes", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            OutlinedButton(
                onClick = { vm.setMinutes(minutes + 5) },
                enabled = minutes < 120,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("+5", color = Cc.colors.primary) }
        }
        Text(
            "This time is kept free in your plan every day. 15 to 20 minutes is enough. Set 0 to switch it off.",
            style = MaterialTheme.typography.bodySmall,
            color = Cc.colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { runCatching { nav.navigate(Routes.TELUGU) } },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Open Telugu practice", color = Cc.colors.primary) }
            OutlinedButton(
                onClick = { runCatching { nav.navigate(Routes.COMPILATION) } },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Monthly digests", color = Cc.colors.primary) }
        }
    }
}

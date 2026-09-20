package com.naveen.civilscompanion.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibrarySettingsViewModel @Inject constructor(
    private val kv: KvRepository,
    private val repo: LibraryRepository,
) : ViewModel() {
    val day: StateFlow<LibraryDay> = kv.observe(LIBRARY_DAY_KEY, LibraryDay.serializer(), LibraryDay())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryDay())

    val waitingPhotos: StateFlow<Int> = repo.observePending().map { list -> list.count { it.state == "waiting" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun save(value: LibraryDay) {
        viewModelScope.launch { kv.put(LIBRARY_DAY_KEY, LibraryDay.serializer(), value) }
    }

    fun sendPhotos() {
        viewModelScope.launch { repo.flushScans() }
    }
}

/** Shown inside Settings under "Study plan": the optional library day and its date. */
@Composable
fun LibrarySettingsSection(nav: NavHostController, vm: LibrarySettingsViewModel = hiltViewModel()) {
    val day by vm.day.collectAsStateWithLifecycle()
    val waiting by vm.waitingPhotos.collectAsStateWithLifecycle()
    var text by remember(day.date) { mutableStateOf(day.date.orEmpty()) }
    val parsed = parseLibraryDate(text)
    val invalid = text.isNotBlank() && parsed == null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Library day · optional")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = day.enabled, onCheckedChange = { vm.save(day.copy(enabled = it)) })
            Text(
                "I plan to visit a library. The plan will suggest reading library books on that day.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
        }
        if (day.enabled) {
            Text("Next visit: ${libraryDayLabel(day.date)}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    val d = parseLibraryDate(it)
                    if (d != null) vm.save(day.copy(date = d.toString()))
                },
                singleLine = true,
                isError = invalid,
                label = { Text("Date (year-month-day, for example 2026-10-03)") },
                supportingText = { if (invalid) Text("Please write the date like 2026-10-03.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Next Saturday", onClick = { vm.save(day.copy(date = nextWeekday(LocalDate.now(TimeUtil.india), DayOfWeek.SATURDAY).toString())) }, filled = false)
                BigButton("Next Sunday", onClick = { vm.save(day.copy(date = nextWeekday(LocalDate.now(TimeUtil.india), DayOfWeek.SUNDAY).toString())) }, filled = false)
                BigButton("Not decided", onClick = { vm.save(day.copy(date = null)) }, filled = false)
            }
        }
        if (waiting > 0) {
            Text(
                "$waiting scanned photo${if (waiting == 1) " is" else "s are"} waiting for internet.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
            )
            BigButton("Send them now", onClick = vm::sendPhotos, filled = false)
        }
        TextButton(onClick = { nav.navigate(Routes.LIBRARY) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open the Library") }
    }
}

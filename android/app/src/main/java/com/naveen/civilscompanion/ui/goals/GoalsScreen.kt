package com.naveen.civilscompanion.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import java.time.LocalDate

/**
 * SI (Civil) goal: dates, eligibility profile, checklist, body check, PET log, Prelim cut-off.
 * Everything is saved as settings on this tablet, so it works offline. Upright tablet: one column of cards.
 */
@Composable
fun GoalsScreen(nav: NavHostController, vm: GoalsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val compact = isCompact()
    val today = remember { LocalDate.now(TimeUtil.india) }

    val dates: @Composable () -> Unit = { DatesCard(ui.applied, today, vm::setApplied) }
    val profile: @Composable () -> Unit = { ProfileCard(ui.profile, vm::updateProfile) }
    val checklist: @Composable () -> Unit = { ChecklistCard(ui.profile, ui.checklist, vm::setChecked) }
    val body: @Composable () -> Unit = { PmtCard(ui.profile, ui.pmt, vm::updatePmt) }
    val pet: @Composable () -> Unit = { PetCard(ui.profile, ui.petLog, vm::addPet, vm::deletePet, vm::newId) }
    val cutoff: @Composable () -> Unit = { CutoffCard(ui.profile) }

    Column(
        modifier = Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 24.dp)
            .padding(bottom = if (compact) 72.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            title = "SI (Civil) goal",
            subtitle = "Dates, your eligibility, the checklist, body check and running log for the Sub-Inspector exam.",
            actions = {
                OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("Back", color = Cc.colors.primary)
                }
            },
        )
        if (compact) {
            dates()
            profile()
            checklist()
            body()
            pet()
            cutoff()
            UnknownCard()
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    dates()
                    profile()
                    checklist()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    body()
                    pet()
                    cutoff()
                    UnknownCard()
                }
            }
        }
    }
}

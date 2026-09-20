package com.naveen.civilscompanion.ui.revise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc

/** Shown inside Settings under "Study plan": revision time, cards a day, Sunday review, link to the rules. */
@Composable
fun ReviseSettingsSection(nav: NavHostController) {
    val vm: ReviseViewModel = hiltViewModel()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Revision", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        ReviseSettingsBlock(nav, vm)
    }
}

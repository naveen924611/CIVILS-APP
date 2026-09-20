package com.naveen.civilscompanion.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc

/** Real Settings arrive in M7. Until then this only offers Log out, so login can be re-tested. */
@Composable
fun SettingsPlaceholder(onLogout: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Settings", style = MaterialTheme.typography.displayMedium, color = Cc.colors.ink)
        Text(
            "The full settings screen is built in milestone M7.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cc.colors.muted,
        )
        OutlinedButton(
            onClick = onLogout,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Log out") }
    }
}

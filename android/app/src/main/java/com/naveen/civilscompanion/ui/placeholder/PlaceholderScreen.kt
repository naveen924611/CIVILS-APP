package com.naveen.civilscompanion.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.nav.Destination

/** Stand-in for screens that arrive in later milestones. */
@Composable
fun PlaceholderScreen(dest: Destination, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(dest.label, style = MaterialTheme.typography.displayMedium, color = Cc.colors.ink)
        Text(
            "This screen is built in milestone ${dest.milestone}.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cc.colors.muted,
        )
    }
}

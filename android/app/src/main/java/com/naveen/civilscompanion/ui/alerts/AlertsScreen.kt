package com.naveen.civilscompanion.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naveen.civilscompanion.theme.Cc

/** Alerts (spec 6.7): a history of notifications in the same card style as the system ones. */
@Composable
fun AlertsScreen(onOpenBriefs: () -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val colors = Cc.colors
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Alerts",
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (alerts.any { it.unread }) {
                    OutlinedButton(
                        onClick = vm::markAllRead,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Mark all as read") }
                }
            }
            if (alerts.isEmpty()) {
                Text(
                    "Nothing yet. Brief notices and reminders will appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(alerts, key = { it.id }) { alert ->
                        AlertCard(
                            alert,
                            onPlay = {
                                vm.openBrief(alert, play = true)
                                onOpenBriefs()
                            },
                            onRead = {
                                vm.openBrief(alert, play = false)
                                onOpenBriefs()
                            },
                            onSeen = { vm.markRead(alert.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: AlertUi, onPlay: () -> Unit, onRead: () -> Unit, onSeen: () -> Unit) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .clickable(enabled = alert.unread, onClick = onSeen)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alert.unread) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
            Text("Civils Companion · ${alert.whenText}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(alert.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        if (alert.body.isNotBlank()) {
            Text(alert.body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        if (alert.kind == "brief_ready" && alert.briefId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlay, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Play now")
                }
                OutlinedButton(onClick = onRead, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Read")
                }
            }
        }
    }
}

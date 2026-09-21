package com.naveen.civilscompanion.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.rememberMicPermission
import com.naveen.civilscompanion.ui.nav.Routes

/**
 * The always-available microphone button (spec 6.4). Tap, speak, and the app either does what you said
 * (offline commands such as "what's next") or takes it as a question. Hidden on the Ask screen, which has its own mic.
 */
@Composable
fun FloatingMic(nav: NavHostController, modifier: Modifier = Modifier, vm: FloatingMicViewModel = hiltViewModel()) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val ui by vm.ui.collectAsStateWithLifecycle()
    val timerMs by vm.timerMs.collectAsStateWithLifecycle()
    val requestMic = rememberMicPermission(onDenied = vm::micDenied) { vm.startListening(nav) }
    if (route == Routes.ASK) return

    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (timerMs > 0L) TimerChip(timerMs, onCancel = vm::cancelTimer)
        if (ui.listening || ui.message.isNotEmpty()) {
            MicMessage(ui, onOpenAsk = {
                vm.dismiss()
                nav.navigate(Routes.ASK)
            })
        }
        MicButton(listening = ui.listening, onClick = { if (ui.listening) vm.cancel() else requestMic() })
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val c = Cc.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (listening) c.dangerTint else c.primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (listening) "Stop listening" else "Speak to the app" },
        contentAlignment = Alignment.Center,
    ) {
        PathIcon("mic", CcPaths.MIC, tint = if (listening) c.onDangerTint else c.onPrimary, size = 26.dp)
    }
}

@Composable
private fun MicMessage(ui: MicUi, onOpenAsk: () -> Unit) {
    val c = Cc.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ui.listening) {
            Text("Listening...", style = MaterialTheme.typography.labelLarge, color = c.primary)
            if (ui.partial.isNotEmpty()) Text(ui.partial, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        } else {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (ui.canOpenAsk) {
                Text(
                    "Open Ask",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.primary,
                    modifier = Modifier.clickable(onClick = onOpenAsk).padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun TimerChip(remainingMs: Long, onCancel: () -> Unit) {
    val c = Cc.colors
    val seconds = (remainingMs + 999) / 1000
    val label = "Timer %d:%02d".format(seconds / 60, seconds % 60)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.accentTint)
            .clickable(onClick = onCancel)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "$label. Tap to cancel." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onAccentTint)
        Text("tap to cancel", style = MaterialTheme.typography.labelSmall, color = c.onAccentTint)
    }
}

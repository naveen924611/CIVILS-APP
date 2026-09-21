package com.naveen.civilscompanion.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.isCompact

// The same dark player look as the Briefs player.
private val BarText = Color(0xFFF6F3EC)
private val BarMuted = Color(0xFFB8B2A6)
private val BarLine = Color(0xFF5E5A52)
private val BarDark = Color(0xFF1C1B19)

/** Bottom bar of the Reader: sentence back/forward, play or pause, voice, speed and sleep timer. */
@Composable
internal fun ReadPlayer(s: ReadUiState, vm: ReadViewModel) {
    val total = s.sentences.size
    val compact = isCompact()
    Row(
        Modifier.fillMaxWidth().background(Cc.colors.playerBar).padding(start = if (compact) 16.dp else 32.dp, end = 104.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        Round("Back", 52.dp, filled = false, description = "Previous sentence", enabled = total > 0) { vm.stepSentence(-1) }
        Round(
            if (s.speaking) "Pause" else "Play", 64.dp, filled = true,
            description = if (s.speaking) "Pause reading" else "Start reading aloud", enabled = total > 0,
        ) { vm.toggleReading() }
        Round("Next", 52.dp, filled = false, description = "Next sentence", enabled = total > 0) { vm.stepSentence(1) }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val line = when {
                total == 0 -> "No text on this page"
                s.current >= 0 -> "Sentence ${s.current + 1} of $total"
                else -> "$total sentences · tap one to start there"
            }
            Text(line, color = BarText, fontSize = 15.sp)
            Text("Voice: this tablet · works offline", color = BarMuted, fontSize = 13.sp)
        }

        BarButton("${speedLabel(s.speed)}x", "Reading speed") { vm.cycleSpeed() }
        BarButton(if (s.sleepMinutes == 0) "Sleep off" else "Sleep ${s.sleepMinutes} min", "Sleep timer") { vm.cycleSleep() }
    }
}

private fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0" else speed.toString()

@Composable
private fun Round(label: String, size: androidx.compose.ui.unit.Dp, filled: Boolean, description: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(BarText.copy(alpha = alpha))
                else Modifier.border(1.dp, BarLine.copy(alpha = alpha), CircleShape),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) BarDark else BarText.copy(alpha = alpha),
            fontSize = if (filled) 15.sp else 13.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BarButton(label: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, BarLine, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BarText, fontSize = 14.sp)
    }
}

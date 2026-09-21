package com.naveen.civilscompanion.ui.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc

/** A round-cornered choice button (48 dp tall). selected = filled with the main colour. */
@Composable
fun TChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cc.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary else c.rail)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onPrimary else c.ink)
    }
}

/** A text-only button (48 dp tall) for small actions under a card. */
@Composable
fun TAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (danger) Cc.colors.onDangerTint else Cc.colors.primary,
        )
    }
}

/** A coloured message strip (amber). */
@Composable
fun TNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Cc.colors.onAccentTint,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(14.dp),
    )
}

/** A horizontal bar showing part of a whole (0..1). */
@Composable
fun TBar(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Cc.colors.primary) {
    Box(modifier.fillMaxWidth().heightIn(min = 10.dp).clip(RoundedCornerShape(5.dp)).background(Cc.colors.rail)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).heightIn(min = 10.dp).clip(RoundedCornerShape(5.dp)).background(color),
        )
    }
}

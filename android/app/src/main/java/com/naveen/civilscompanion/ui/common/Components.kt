package com.naveen.civilscompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc

/** Page title with an optional line under it and buttons on the right. */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = Cc.colors.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A card: surface colour, hairline border, 12 dp corners. Pass onClick to make it tappable (48 dp minimum height). */
@Composable
fun CcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val base = modifier
        .clip(shape)
        .background(Cc.colors.surface)
        .border(BorderStroke(1.dp, Cc.colors.border), shape)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Small rounded label. tone: 0 neutral, 1 green (done, good), 2 amber (attention), 3 red (problem). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: Int = 0) {
    val c = Cc.colors
    val (bg, fg) = when (tone) {
        1 -> c.primaryTint to c.onPrimaryTint
        2 -> c.accentTint to c.onAccentTint
        3 -> c.dangerTint to c.onDangerTint
        else -> c.rail to c.muted
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Main button: at least 48 dp tall (touch target). filled = false gives the outlined version. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    if (filled) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = Cc.colors.primary, contentColor = Cc.colors.onPrimary),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, Cc.colors.border),
        ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Cc.colors.primary) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted, modifier = modifier)
}

/** Centered message for an empty list ("Nothing here yet"). */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted)
    }
}

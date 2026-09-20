package com.naveen.civilscompanion.ui.briefs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.Fraunces
import kotlin.math.abs

/** Briefs (spec 6.2): item list on the left, the story on the right, the audio player along the bottom. */
@Composable
fun BriefsScreen(vm: BriefsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ItemListPane(s, vm, Modifier.width(380.dp).fillMaxHeight())
            DetailPane(s, Modifier.weight(1f).fillMaxHeight())
        }
        PlayerBar(s, vm)
    }
}

@Composable
private fun ItemListPane(s: BriefsUiState, vm: BriefsViewModel, modifier: Modifier) {
    val colors = Cc.colors
    Column(
        modifier = modifier
            .drawBehind {
                drawLine(colors.border, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.selected?.title ?: "Briefs", style = MaterialTheme.typography.displaySmall, color = colors.ink)
        if (s.headline.isNotBlank()) {
            Text(s.headline, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PastBriefsButton(s, vm)
            OutlinedButton(
                onClick = { vm.refresh() },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Refresh") }
        }
        Button(
            onClick = { vm.prepareNow() },
            enabled = !s.busy,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (s.busy) "Preparing…" else "Prepare a brief now") }

        if (s.note.isNotBlank()) {
            Text(s.note, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        s.message?.let { Banner(it, onDismiss = vm::dismissMessage) }

        if (s.items.isEmpty()) {
            Text(
                if (s.briefs.isEmpty()) {
                    "No briefs yet. Your first one arrives at the time set in Settings, or tap “Prepare a brief now”."
                } else {
                    "This brief has no items."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.items, key = { it.id }) { item ->
                    ItemCard(item, selected = item.id == s.detail?.id, onClick = { vm.selectItem(item.id) })
                }
            }
        }
    }
}

@Composable
private fun PastBriefsButton(s: BriefsUiState, vm: BriefsViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = s.briefs.isNotEmpty(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Past briefs ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.briefs.forEach { row ->
                DropdownMenuItem(
                    text = {
                        val suffix = if (row.status == "ready") "" else " · ${row.status}"
                        Text("${row.title} · ${row.dayText}$suffix")
                    },
                    onClick = {
                        open = false
                        vm.selectBrief(row.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    val colors = Cc.colors
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.accentTint).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
    }
}

@Composable
private fun ItemCard(item: ItemUi, selected: Boolean, onClick: () -> Unit) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(12.dp)
    val state = when {
        item.playing -> "playing"
        item.heard -> "heard"
        else -> ""
    }
    val meta = listOf("${item.number}", item.label, item.duration, state).filter { it.isNotBlank() }.joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.primaryTint else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            meta,
            fontSize = 12.sp,
            color = if (selected) colors.onPrimaryTint else colors.muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.heard && !selected) colors.muted else colors.ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------------------------ detail

@Composable
private fun DetailPane(s: BriefsUiState, modifier: Modifier) {
    val colors = Cc.colors
    val d = s.detail
    if (d == null) {
        Box(modifier.padding(40.dp), contentAlignment = Alignment.Center) {
            Text(
                "Pick a brief item to read it here.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
            )
        }
        return
    }
    val uri = LocalUriHandler.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.papers.forEach { Chip(it, colors.primaryTint, colors.onPrimaryTint) }
            if (d.isAp) Chip("Andhra Pradesh", colors.accentTint, colors.onAccentTint)
        }
        Text(d.title, style = MaterialTheme.typography.displaySmall, color = colors.ink)
        Text(
            "Source: ${d.source} · summary written by AI, check the linked source",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Text(d.summary, style = MaterialTheme.typography.bodyLarge, color = colors.ink)

        if (d.facts.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.accentTint).padding(18.dp, 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Must remember", fontWeight = FontWeight.SemiBold, color = colors.onAccentTint)
                d.facts.forEach { f ->
                    Text("${f.q.trim()}  →  ${f.a.trim()}", style = MaterialTheme.typography.bodyMedium, color = colors.onAccentTint)
                }
            }
        }
        if (d.mainsAngle.isNotBlank()) {
            val shape = MaterialTheme.shapes.medium
            Column(
                Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.border, shape).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Mains angle", fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(d.mainsAngle, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d.cardCount > 0) {
                Text(
                    "✓ ${d.cardCount} facts added as revision cards",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                )
            }
            OutlinedButton(
                onClick = { runCatching { uri.openUri(d.url) } },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Open the source") }
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------------------------ player

private val SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 0.75f)
private val SLEEPS = listOf<Int?>(null, 15, 30, 45, 60)
private val BarText = Color(0xFFF6F3EC)
private val BarMuted = Color(0xFFB8B2A6)
private val BarLine = Color(0xFF5E5A52)
private val BarTrack = Color(0xFF3F3C36)

@Composable
private fun PlayerBar(s: BriefsUiState, vm: BriefsViewModel) {
    val colors = Cc.colors
    val p = if (s.playingThisBrief) s.player else null
    val fraction = if (p != null && p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().height(96.dp).background(colors.playerBar).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RoundButton("−15", 48.dp, filled = false, description = "Back 15 seconds", enabled = p != null) { vm.back15() }
        RoundButton(
            if (p?.isPlaying == true) "❚❚" else "▶", 60.dp, filled = true,
            description = if (p?.isPlaying == true) "Pause" else "Play",
            enabled = s.items.isNotEmpty(),
        ) { vm.togglePlay() }
        RoundButton("+15", 48.dp, filled = false, description = "Forward 15 seconds", enabled = p != null) { vm.forward15() }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val line = if (p != null) {
                "Item ${p.index + 1} of ${p.itemIds.size} · ${BriefFormat.clock(p.positionMs)} / ${BriefFormat.clock(p.durationMs)}"
            } else if (s.items.isEmpty()) {
                "No brief to play yet"
            } else {
                "Press play to listen to this brief"
            }
            Text(line, color = BarText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SeekBar(fraction, enabled = p != null, progress = colors.playerProgress, onSeek = vm::seek)
        }

        val speed = s.player.speed
        BarButton(BriefFormat.speed(speed), "Playback speed") {
            val at = SPEEDS.indexOfFirst { abs(it - speed) < 0.01f }
            vm.setSpeed(SPEEDS[(at + 1) % SPEEDS.size])
        }
        val sleepText = when {
            s.player.sleepMinutes == null -> "Sleep off"
            s.player.sleepRemainingMs > 0 -> "Sleep ${BriefFormat.clock(s.player.sleepRemainingMs)}"
            else -> "Sleep ${s.player.sleepMinutes} min"
        }
        BarButton(sleepText, "Sleep timer") {
            val at = SLEEPS.indexOf(s.player.sleepMinutes)
            vm.setSleep(SLEEPS[(at + 1) % SLEEPS.size])
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, enabled: Boolean, progress: Color, onSeek: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { offset -> onSeek(offset.x / size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(BarTrack)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(progress))
        }
    }
}

@Composable
private fun RoundButton(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    filled: Boolean,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
            color = if (filled) Color(0xFF1C1B19) else BarText.copy(alpha = alpha),
            fontSize = if (filled) 20.sp else 13.sp,
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
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = BarText, fontSize = 15.sp) }
}

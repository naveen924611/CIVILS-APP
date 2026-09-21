package com.naveen.civilscompanion.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel

/** Storage page: everything in the Settings storage section, plus backups. */
@Composable
fun StorageScreen(nav: NavHostController, vm: StorageViewModel = hiltViewModel()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle(
                "Storage",
                subtitle = "What is saved, how to keep it small, and your backups.",
                actions = { BigButton("Settings", onClick = { runCatching { nav.popBackStack() } }, filled = false) },
            )
            StorageSection(vm, showBackups = true)
            AiUsageSection(vm)
        }
    }
}

/** Usage bar, limit, Sync now, delete old audio, Export my data (and backups on the Storage page). */
@Composable
fun StorageSection(vm: StorageViewModel, showBackups: Boolean) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    // A finished export: offer it to the share sheet (save to Drive, send by email, copy to a USB drive...).
    LaunchedEffect(s.exportFile) {
        val file = s.exportFile ?: return@LaunchedEffect
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Save or share your data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: IllegalArgumentException) {
            // the file could not be shared; the message below tells the owner where it is
        } catch (e: android.content.ActivityNotFoundException) {
            // no app can take a zip file
        }
        vm.exportHandled()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            val usage = s.usage
            SectionLabel("Server storage")
            if (usage == null) {
                Text("Not loaded yet. Open this when you are online.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            } else {
                val parts = StorageLogic.parts(usage.audioBytes, usage.documentsBytes, usage.studyDataBytes, usage.waitingBytes, usage.backupsBytes)
                val total = parts.sumOf { it.bytes }
                Text(
                    "${StorageLogic.bytes(total)} of ${s.limitGb} GB used",
                    style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink,
                )
                UsageBar(parts.map { it.bytes }, StorageLogic.limitBytes(s.limitGb))
                val palette = barColors()
                parts.forEachIndexed { i, part -> LegendRow(part.label, StorageLogic.bytes(part.bytes), palette[i % palette.size]) }
                if (usage.waitingJobs > 0) {
                    Text("${usage.waitingJobs} request(s) are waiting to be answered.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
                if (usage.diskTotalBytes > 0) {
                    Text(
                        "Server disk: ${StorageLogic.bytes(usage.diskFreeBytes)} free of ${StorageLogic.bytes(usage.diskTotalBytes)}.",
                        style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                    )
                }
            }
            Text(
                "On this tablet: ${StorageLogic.bytes(s.tabletAudioBytes)} of saved audio.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            )
            SectionLabel("Limit")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.changeLimit(-5) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text("−5 GB") }
                Text("${s.limitGb} GB", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                OutlinedButton(onClick = { vm.changeLimit(5) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text("+5 GB") }
            }
            Text(
                "The bar turns to the limit you set. Delete old audio to make room.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Sync now", onClick = vm::syncNow, filled = false, enabled = s.busy == null)
            BigButton("Export my data", onClick = vm::exportData, filled = false, enabled = s.busy == null)
        }
        BigButton("Delete audio older than 60 days", onClick = { confirmDelete = true }, filled = false, enabled = s.busy == null)
        s.lastSync?.let {
            Text("Last sync: ${it.take(16).replace('T', ' ')} UTC", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        s.busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted) }
        s.message?.let { msg ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Cc.colors.accentTint).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, color = Cc.colors.onAccentTint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
            }
        }
        if (showBackups) BackupsCard(vm)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete old audio?") },
            text = { Text("Audio older than 60 days is removed from the server and this tablet. Notes and text stay. You can make new audio again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteOldAudio(60) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun BackupsCard(vm: StorageViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Backups on the server")
        Text(
            "A backup is made every night and the newest 7 are kept. Each holds your study data, documents and audio.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
        if (s.backups.isEmpty()) Text("No backups yet.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        s.backups.forEach { b ->
            Text("${StorageLogic.backupLabel(b.name)} · ${StorageLogic.bytes(b.bytes)}", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        }
        BigButton("Make a backup now", onClick = vm::backupNow, filled = false, enabled = s.busy == null)
        Text(
            "To restore one, follow docs/server-runbook.md on the server computer.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
    }
}

/** "AI usage": how much of today's free quota is used. Paid usage is off. */
@Composable
fun AiUsageSection(vm: StorageViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("AI usage")
        val ai = s.ai
        if (ai == null) {
            Text("Not loaded yet. Open this when you are online.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        } else {
            Text("Free quota used today: ${StorageLogic.percent(ai.fraction)}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
            ai.providers.forEach { (name, use) ->
                Text("$name: ${use.used} of ${use.limit} requests", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
            if (ai.level >= 1) {
                Text(
                    "The free quota is nearly used, so the app is saving it for briefs and your questions.",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.onAccentTint,
                )
            }
        }
        Text("Paid usage: off. This app only uses free plans.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
}

@Composable
private fun barColors(): List<Color> =
    listOf(Cc.colors.primary, Cc.colors.onAccentTint, Cc.colors.playerProgress, Cc.colors.muted, Cc.colors.onDangerTint)

@Composable
private fun UsageBar(sizes: List<Long>, limit: Long) {
    val shape = RoundedCornerShape(8.dp)
    val colors = barColors()
    val used = sizes.sum()
    val rest = (limit - used).coerceAtLeast(0L)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(shape).background(Cc.colors.border)) {
        sizes.forEachIndexed { i, size ->
            // a part is drawn at least a sliver wide so small ones stay visible
            val w = (size.toFloat() / (used + rest).coerceAtLeast(1L).toFloat()).coerceAtLeast(0.01f)
            Box(Modifier.weight(w).fillMaxSize().background(colors[i % colors.size]))
        }
        val tail = rest.toFloat() / (used + rest).coerceAtLeast(1L).toFloat()
        if (tail > 0f) Box(Modifier.weight(tail).fillMaxSize())
    }
}

@Composable
private fun LegendRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
    }
}

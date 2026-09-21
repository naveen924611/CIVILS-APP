package com.naveen.civilscompanion.ui.compilation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.Compilation
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.MarkdownText
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact

/** Monthly current-affairs digests (spec M12): the list on the left, the digest on the right, with share buttons. */
@Composable
fun CompilationScreen(nav: NavHostController, vm: CompilationViewModel = hiltViewModel()) {
    val digests by vm.list.collectAsStateWithLifecycle()
    val building by vm.building.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = digests.firstOrNull { it.id == selectedId } ?: digests.firstOrNull()
    val context = LocalContext.current
    val compact = isCompact()

    LaunchedEffect(Unit) {
        vm.pdf.collect { ready ->
            val intent = if (ready.share) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, ready.uri)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply { setDataAndType(ready.uri, "application/pdf") }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launchSafely(context, if (ready.share) Intent.createChooser(intent, "Share the digest") else intent) {
                vm.clearMessage()
            }
        }
    }

    if (compact) {
        // upright tablet: buttons and a row of months on top, the chosen digest below
        Column(
            Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("Monthly digests", subtitle = "Current affairs, notes and mistakes of each month")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    if (building) "Preparing..." else "Make last month's digest",
                    { vm.build(CompilationLogic.previousMonth(vm.today())) },
                    enabled = !building,
                    modifier = Modifier.weight(1f),
                )
                BigButton(
                    "Make this month so far",
                    { vm.build(CompilationLogic.currentMonth(vm.today())) },
                    enabled = !building,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            message?.let { note ->
                CcCard(Modifier.fillMaxWidth(), onClick = vm::clearMessage) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    Text("Tap to dismiss", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
            if (digests.isEmpty()) {
                Text(
                    "Nothing yet. A digest is made by itself on the 1st of every month, or use the buttons above.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(digests, key = { it.id }) { c ->
                        CcCard(Modifier.width(170.dp), onClick = { selectedId = c.id }) {
                            Text(CompilationLogic.monthLabel(c.month), style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (c.id == selected?.id) Pill("Open", tone = 1)
                                if (!c.pdfPath.isNullOrBlank()) Pill("PDF", tone = 0)
                                if (CompilationLogic.isEmptyDigest(c.contentMd)) Pill("Empty", tone = 2)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (selected == null) {
                    EmptyState("No digest selected", "Your monthly digests will show here.")
                } else {
                    DigestView(selected, busy, vm)
                }
            }
        }
    } else Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        Column(
            Modifier.width(340.dp).fillMaxHeight().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("Monthly digests", subtitle = "Current affairs, notes and mistakes of each month")
            BigButton(
                if (building) "Preparing..." else "Make last month's digest",
                { vm.build(CompilationLogic.previousMonth(vm.today())) },
                enabled = !building,
                modifier = Modifier.fillMaxWidth(),
            )
            BigButton(
                "Make this month so far",
                { vm.build(CompilationLogic.currentMonth(vm.today())) },
                enabled = !building,
                filled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { note ->
                CcCard(Modifier.fillMaxWidth(), onClick = vm::clearMessage) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    Text("Tap to dismiss", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
            if (digests.isEmpty()) {
                Text(
                    "Nothing yet. A digest is made by itself on the 1st of every month, or use the buttons above.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(digests, key = { it.id }) { c ->
                        CcCard(Modifier.fillMaxWidth(), onClick = { selectedId = c.id }) {
                            Text(CompilationLogic.monthLabel(c.month), style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (c.id == selected?.id) Pill("Open", tone = 1)
                                if (!c.pdfPath.isNullOrBlank()) Pill("PDF", tone = 0)
                                if (CompilationLogic.isEmptyDigest(c.contentMd)) Pill("Empty", tone = 2)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 24.dp)) {
            if (selected == null) {
                EmptyState("No digest selected", "Your monthly digests will show here.")
            } else {
                DigestView(selected, busy, vm)
            }
        }
    }
}

@Composable
private fun DigestView(c: Compilation, busy: Boolean, vm: CompilationViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(CompilationLogic.monthLabel(c.month) + " - about ${CompilationLogic.readingMinutes(c.contentMd)} min read")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Share text", {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, c.title.ifBlank { CompilationLogic.monthLabel(c.month) })
                    putExtra(Intent.EXTRA_TEXT, c.contentMd)
                }
                launchSafely(context, Intent.createChooser(send, "Share the digest")) {}
            }, filled = false)
            BigButton(if (busy) "Getting the PDF..." else "Open PDF", { vm.fetchPdf(c, share = false) }, enabled = !busy && !c.pdfPath.isNullOrBlank(), filled = false)
            BigButton("Share PDF", { vm.fetchPdf(c, share = true) }, enabled = !busy && !c.pdfPath.isNullOrBlank())
        }
        if (c.contentMd.isBlank()) {
            EmptyState("This digest is empty", "Try making it again after more briefs and notes are in.")
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                MarkdownText(c.contentMd, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Starts another app (share sheet, PDF viewer). If none can handle it, `onMissing` runs. */
private fun launchSafely(context: Context, intent: Intent, onMissing: () -> Unit) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onMissing()
    } catch (e: SecurityException) {
        onMissing()
    }
}

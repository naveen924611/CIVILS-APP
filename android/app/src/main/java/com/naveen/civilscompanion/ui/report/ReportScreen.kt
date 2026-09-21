package com.naveen.civilscompanion.ui.report

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.tests.TAction
import com.naveen.civilscompanion.ui.tests.TNotice

/** OWNER: weekly report (M10). Route report. Called from SheetsRoutes because MainActivity registers only sheetsRoutes. */
fun NavGraphBuilder.reportRoutes(nav: NavHostController) {
    composable(Routes.REPORT) { ReportScreen(nav) }
}

/** The weekly report: numbers, covered topics, weak spots, next week's changes, last-month mode. */
@Composable
fun ReportScreen(nav: NavHostController, vm: ReportViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.pdfReady.collect { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, "Save, share or print the report"))
            } catch (e: ActivityNotFoundException) {
                // no app can take a PDF; nothing to do
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }

    val report = ui.selected
    val d = ui.data
    val headerPane: @Composable () -> Unit = {
        ScreenTitle(
            "Weekly report",
            subtitle = if (report == null) "Every Sunday evening" else ReportLogic.weekTitle(d.weekStart.ifBlank { report.weekStart }, d.weekEnd),
        )
        ui.message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TNotice(msg, Modifier.weight(1f))
                TAction("OK", onClick = vm::clearMessage)
            }
        }
    }
    val bodyPane: @Composable () -> Unit = {
        val days = ui.lastMonthDays
        if (days != null) LastMonthCard(days, ui.lastMonthExam, onSheets = { nav.navigate(Routes.SHEETS) })
        if (report == null) {
            CcCard(Modifier.fillMaxWidth()) {
                Text("No report yet", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    "Your first report arrives on Sunday evening. You can also make this week's report now (it needs the internet once).",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
                BigButton("Make this week's report", onClick = vm::makeNow)
            }
        } else {
            if (d.narrative.isNotBlank()) {
                CcCard(Modifier.fillMaxWidth()) { Text(d.narrative, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.ink) }
            }
            NumbersCard(d)
            CoveredCard(d)
            WeakCard(d)
            NextWeekCard(d, ui.accepted, onAccept = vm::acceptPlan)
        }
    }
    val actionsCard: @Composable () -> Unit = {
        CcCard(Modifier.fillMaxWidth()) {
            if (ui.listening) BigButton("Stop", onClick = vm::stopListening, modifier = Modifier.fillMaxWidth())
            else BigButton("Listen to report", onClick = vm::listen, enabled = report != null, modifier = Modifier.fillMaxWidth())
            BigButton(
                if (ui.busy) "Getting the PDF..." else "Save as PDF or share", onClick = vm::preparePdf,
                enabled = report != null && !ui.busy, filled = false, modifier = Modifier.fillMaxWidth(),
            )
            BigButton("Make this week's report", onClick = vm::makeNow, filled = false, modifier = Modifier.fillMaxWidth())
        }
    }
    val weeksPane: @Composable () -> Unit = {
        SectionLabel("Earlier weeks")
        ui.reports.forEach { r ->
            val chosen = r.id == report?.id
            Text(
                ReportLogic.weekTitle(r.weekStart, ""), style = MaterialTheme.typography.bodyLarge,
                color = if (chosen) Cc.colors.primary else Cc.colors.ink,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.select(r.id) }.padding(vertical = 12.dp),
            )
        }
        BigButton("Revision sheets", onClick = { nav.navigate(Routes.SHEETS) }, filled = false, modifier = Modifier.fillMaxWidth())
    }

    if (isCompact()) {
        Column(
            Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            headerPane()
            actionsCard()
            bodyPane()
            weeksPane()
        }
    } else {
        Row(Modifier.fillMaxSize().background(Cc.colors.background).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                headerPane()
                bodyPane()
            }
            Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actionsCard()
                weeksPane()
            }
        }
    }
}

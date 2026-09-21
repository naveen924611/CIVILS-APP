package com.naveen.civilscompanion.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.tests.TAction
import com.naveen.civilscompanion.ui.tests.TNotice

/** Revision sheets: one page per topic. Also shows today's sheets in last-month mode. Route sheets. */
@Composable
fun SheetsScreen(nav: NavHostController, vm: SheetsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val shown = ui.rows.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.subject.contains(query.trim(), ignoreCase = true) }

    LazyColumn(
        Modifier.fillMaxSize().background(Cc.colors.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle(
                "Revision sheets",
                subtitle = "One page for each topic, made from your notes. Read, listen, or save as PDF.",
                actions = { BigButton("Weekly report", onClick = { nav.navigate(Routes.REPORT) }, filled = false) },
            )
        }
        message?.let { msg ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TNotice(msg, Modifier.weight(1f))
                    TAction("OK", onClick = vm::clearMessage)
                }
            }
        }
        val last = ui.lastMonthDays
        if (last != null) {
            item {
                CcCard(Modifier.fillMaxWidth()) {
                    Pill("Last-month mode", tone = 2)
                    Text(
                        "Your exam is ${if (last == 0) "today" else "in $last days"}. Revise from sheets today, plus your due cards.",
                        style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink,
                    )
                    if (ui.todays.isEmpty()) Text("Make some sheets first, then today's list appears here.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                    ui.todays.forEach { r ->
                        Text(
                            r.title + "  (" + r.subject + ")", style = MaterialTheme.typography.bodyLarge, color = Cc.colors.primary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { nav.navigate(Routes.sheet(r.sheet.topicId)) },
                        )
                    }
                }
            }
        }
        if (ui.missing.isNotEmpty()) {
            item {
                CcCard(Modifier.fillMaxWidth()) {
                    Text("${ui.missing.size} topics have notes but no sheet yet.", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                    BigButton("Make sheets for my studied topics", onClick = { vm.makeAll(ui.missing) })
                    Text("Ten at a time, so the free AI limit is never used up.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search sheets") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.rows.isEmpty()) {
            item {
                Text(
                    "No sheets yet. Write or generate notes for a topic, then make its sheet. They are also made for you every night.",
                    style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        var lastSubject = ""
        shown.forEach { r ->
            val heading = if (r.subject != lastSubject) r.subject else null
            lastSubject = r.subject
            item(key = r.sheet.id) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (heading != null) SectionLabel(heading)
                    CcCard(Modifier.fillMaxWidth(), onClick = { nav.navigate(Routes.sheet(r.sheet.topicId)) }) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Listen: " + SheetLogic.audioLabel(r.sheet.audioSeconds))
                            if (r.sheet.pdfPath != null) Pill("PDF ready", tone = 1)
                        }
                    }
                }
            }
        }
    }
}

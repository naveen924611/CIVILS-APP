package com.naveen.civilscompanion.ui.sheets

import android.content.ActivityNotFoundException
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
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.MarkdownText
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.tests.TAction
import com.naveen.civilscompanion.ui.tests.TNotice

/** One revision sheet with Listen, Save as PDF / Share / Print. Route sheet/{topicId}. */
@Composable
fun SheetScreen(nav: NavHostController, topicId: String, vm: SheetViewModel = hiltViewModel()) {
    LaunchedEffect(topicId) { vm.load(topicId) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.pdfReady.collect { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, "Save, share or print the sheet"))
            } catch (e: ActivityNotFoundException) {
                // no app can take a PDF; nothing to do
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }

    val page by vm.state.collectAsStateWithLifecycle()
    val sheet = page.sheet
    val title = page.topic?.title ?: "Revision sheet"

    Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
        when {
            page.loading -> Text("Opening the sheet...", modifier = Modifier.padding(24.dp), color = Cc.colors.muted)
            sheet == null -> Column(Modifier.fillMaxSize()) {
                EmptyState("No sheet yet", "The sheet for \"$title\" has not been made. It is built from your notes of the topic.", Modifier.weight(1f))
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Make this sheet", onClick = vm::remake)
                    BigButton("Back", onClick = { nav.popBackStack() }, filled = false)
                }
                page.message?.let { TNotice(it, Modifier.padding(horizontal = 24.dp)) }
            }
            else -> {
                val listenCard: @Composable (Modifier) -> Unit = { m ->
                    CcCard(m) {
                        Pill("Listen: " + SheetLogic.audioLabel(sheet.audioSeconds))
                        if (page.listening) BigButton("Stop", onClick = vm::stopListening, modifier = Modifier.fillMaxWidth())
                        else BigButton("Listen", onClick = vm::listen, modifier = Modifier.fillMaxWidth())
                        Text("Read aloud by the tablet's own voice. It works without internet.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    }
                }
                val pdfCard: @Composable (Modifier) -> Unit = { m ->
                    CcCard(m) {
                        BigButton(if (page.busy) "Getting the PDF..." else "Save as PDF, share or print", onClick = vm::preparePdf, enabled = !page.busy, modifier = Modifier.fillMaxWidth())
                        Text(
                            "The share list has Save to Drive, Print and your other apps. Needs the internet once.",
                            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                        )
                    }
                }
                val moreCard: @Composable (Modifier) -> Unit = { m ->
                    CcCard(m) {
                        BigButton("Open my notes", onClick = { nav.navigate(Routes.noteTopic(topicId)) }, filled = false, modifier = Modifier.fillMaxWidth())
                        BigButton("Update this sheet", onClick = vm::remake, filled = false, modifier = Modifier.fillMaxWidth())
                        Text("Sheets also update by themselves every night when your notes change.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                    }
                }
                val messageRow: @Composable () -> Unit = {
                    page.message?.let { msg ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TNotice(msg, Modifier.weight(1f))
                            TAction("OK", onClick = vm::clearMessage)
                        }
                    }
                }
                if (isCompact()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        messageRow()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            listenCard(Modifier.weight(1f))
                            pdfCard(Modifier.weight(1f))
                        }
                        MarkdownText(sheet.contentMd)
                        moreCard(Modifier.fillMaxWidth())
                        BigButton("Back to sheets", onClick = { nav.popBackStack() }, filled = false, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            MarkdownText(sheet.contentMd)
                        }
                        Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listenCard(Modifier.fillMaxWidth())
                            pdfCard(Modifier.fillMaxWidth())
                            moreCard(Modifier.fillMaxWidth())
                            messageRow()
                            BigButton("Back to sheets", onClick = { nav.popBackStack() }, filled = false, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

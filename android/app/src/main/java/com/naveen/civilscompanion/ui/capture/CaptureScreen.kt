package com.naveen.civilscompanion.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
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
import com.naveen.civilscompanion.ui.common.rememberCameraPermission
import com.naveen.civilscompanion.ui.library.Notice
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.util.CaptureFiles
import java.io.File

/** Scan a book page (spec 6.3 and 6.11): take a photo, read it on the tablet, spot facts, save it to the library. */
@Composable
fun CaptureScreen(nav: NavHostController, vm: CaptureViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.start() }
    val s by vm.state.collectAsStateWithLifecycle()

    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        if (ok && path != null) vm.onPhoto(File(path))
    }
    val askCamera = rememberCameraPermission(
        onDenied = { vm.tell("The camera is off for this app. Turn it on in the tablet's app settings, or choose a photo instead.") },
    ) {
        val (file, uri) = CaptureFiles.newPhoto(context)
        pendingPath = file.absolutePath
        takePicture.launch(uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.onPicked(uri)
    }

    var textBox by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(s.version) { textBox = TextFieldValue(s.text) }
    val selection = if (textBox.selection.collapsed) "" else textBox.text.substring(textBox.selection.min.coerceAtLeast(0), textBox.selection.max.coerceAtMost(textBox.text.length))

    Row(
        Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- left: the photo
        Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScreenTitle("Scan a page", subtitle = if (s.documentId != null) "Adding to: ${s.docTitle ?: "this scan"}" else "A new scan is made in your library")
            Box(
                Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(Cc.colors.rail),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = s.preview
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), "The photo you took", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text("No photo yet", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
            }
            BigButton("Take a photo", onClick = askCamera, modifier = Modifier.fillMaxWidth())
            BigButton("Choose a photo", onClick = { pick.launch("image/*") }, filled = false, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.telugu, onCheckedChange = vm::setTelugu)
                Text("This page is in Telugu", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            }
            Text(
                if (s.telugu) "The tablet cannot read Telugu by itself. Save the page and the server's AI reads it when you are online."
                else "English pages are read on the tablet, with no internet.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
            if (s.pagesSaved > 0) Pill("${s.pagesSaved} page${if (s.pagesSaved == 1) "" else "s"} saved in this scan", tone = 1)
        }

        // ---- middle: the text
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.message?.let { Notice(it, onDismiss = vm::dismissMessage) }
            if (s.reading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.height(28.dp).width(28.dp))
                    Text("Reading the page...", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
            }
            OutlinedTextField(
                value = textBox,
                onValueChange = { textBox = it; vm.setText(it.text) },
                label = { Text("Text from the photo (fix any mistakes; select words to save just those)") },
                enabled = s.photoPath != null && !s.reading,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Save as point", onClick = { vm.saveHighlight("point", selection) }, filled = false)
                BigButton("Must remember", onClick = { vm.saveHighlight("must", selection) }, filled = false)
                BigButton("Make flashcard", onClick = { vm.saveHighlight("card", selection) }, filled = false)
                BigButton("Explain simply", onClick = { if (vm.prepareAsk(selection)) nav.navigate(Routes.ASK) }, filled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 96.dp)) {
                BigButton("Save this page", onClick = vm::savePage, enabled = s.photoPath != null && !s.saving)
                val docId = s.documentId
                if (docId != null) {
                    BigButton("Finish and read", onClick = { nav.navigate(Routes.readDoc(docId)) }, filled = false)
                } else {
                    BigButton("Back to the Library", onClick = { nav.navigate(Routes.LIBRARY) }, filled = false)
                }
            }
        }

        // ---- right: what was found
        Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpottedPanel(s, vm)
            KnownPanel(s)
            WaitingPanel(s, vm, selection)
        }
    }
}

@Composable
private fun SpottedPanel(s: CaptureUiState, vm: CaptureViewModel) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Spotted on this page")
        val fresh = s.facts.filter { !it.known }
        if (fresh.isEmpty()) {
            Text(
                if (s.facts.isEmpty()) "Articles, amendments, dates, numbers and committees show up here." else "Everything spotted is already in your notes.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
        for (row in fresh.take(30)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(row.fact.text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
                    Text(row.fact.kind.label, style = MaterialTheme.typography.labelSmall, color = Cc.colors.muted)
                }
                if (row.fact.text in s.cardsMade) Pill("Added", tone = 1)
                else androidx.compose.material3.TextButton(onClick = { vm.makeCard(row) }, modifier = Modifier.height(48.dp)) { Text("Flashcard") }
            }
        }
        if (fresh.isNotEmpty()) BigButton("Turn into flashcards", onClick = vm::makeAllCards, filled = false, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun KnownPanel(s: CaptureUiState) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Already in your notes")
        val known = s.facts.filter { it.known }
        if (known.isEmpty()) {
            Text("Nothing repeated. Only new points are added.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        }
        for (row in known.take(20)) {
            Text("${row.fact.text} is already in your notes", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
        }
    }
}

@Composable
private fun WaitingPanel(s: CaptureUiState, vm: CaptureViewModel, selection: String) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Waiting for internet")
        when (s.mergeStatus) {
            null -> {
                Text(
                    "Ask the AI to add this page to your notes. It is queued now and runs when you are online. You get a message when it is done.",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                )
                BigButton("Add to my notes (AI)", onClick = { vm.queueMerge(selection) }, filled = false, enabled = s.text.isNotBlank(), modifier = Modifier.fillMaxWidth())
            }
            "done" -> {
                Pill("Added to your notes", tone = 1)
                if (s.mergeSummary.isNotBlank()) Text(s.mergeSummary, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            }
            "failed" -> Pill("Could not be added. Try again later.", tone = 3)
            "running" -> Pill("Working on it", tone = 2)
            else -> Pill("Waiting for internet", tone = 2)
        }
        if (s.waitingPhotos > 0) {
            Text(
                "${s.waitingPhotos} photo${if (s.waitingPhotos == 1) " is" else "s are"} waiting to be sent to the server.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
            BigButton("Send now", onClick = vm::sendWaiting, filled = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

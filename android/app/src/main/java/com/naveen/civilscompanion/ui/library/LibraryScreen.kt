package com.naveen.civilscompanion.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.nav.Routes

/** Library (spec 6.9): my uploads by subject, recommended free material, optional books and the library-day list. */
@Composable
fun LibraryScreen(nav: NavHostController, vm: LibraryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.sendWaitingQuietly() }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadPdf(uri)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.uploadImages(uris)
    }

    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(
            title = "Library",
            subtitle = "Your books and notes, and free official material",
            actions = {
                BigButton("Upload PDF", onClick = { pickPdf.launch(arrayOf("application/pdf")) }, filled = false, enabled = !s.busy)
                BigButton("Upload images", onClick = { pickImages.launch(arrayOf("image/*")) }, filled = false, enabled = !s.busy)
                BigButton("Scan with camera", onClick = { nav.navigate(Routes.CAPTURE) })
            },
        )
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.message?.let { Notice(it, onDismiss = vm::dismissMessage) }
        if (s.pending.isNotEmpty()) {
            val waiting = s.pending.count { it.state == "waiting" }
            if (waiting > 0) {
                Notice(
                    "$waiting photo${if (waiting == 1) "" else "s"} waiting to be sent. They go up by themselves when you are online.",
                    actionLabel = "Send now", onAction = vm::sendWaitingScans,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TabChip("My uploads", s.tab == LibraryTab.Mine) { vm.selectTab(LibraryTab.Mine) }
            TabChip("Recommended", s.tab == LibraryTab.Recommended) { vm.selectTab(LibraryTab.Recommended) }
        }
        if (s.tab == LibraryTab.Mine) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Search titles and the text inside your documents") },
                modifier = Modifier.fillMaxWidth(),
            )
            MyUploads(s, vm, nav)
        } else {
            RecommendedPane(s, vm, nav)
        }
    }
}

@Composable
private fun MyUploads(s: LibraryUiState, vm: LibraryViewModel, nav: NavHostController) {
    val searching = s.query.trim().length >= 2
    if (s.groups.isEmpty() && s.hits.isEmpty()) {
        if (s.documentCount == 0) {
            EmptyState("Nothing here yet", "Upload a PDF or photos, or scan a page with the camera. Then read it here, even offline.")
        } else {
            EmptyState("No match", "Nothing in your library matches \"${s.query}\".")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (searching && s.hits.isNotEmpty()) {
            item(key = "hits-label") { SectionLabel("Found inside your documents") }
            items(s.hits, key = { "hit-" + it.documentId + "-" + it.page + "-" + it.snippet.hashCode() }) { hit ->
                CcCard(Modifier.fillMaxWidth(), onClick = { vm.openHit(hit) { nav.navigate(Routes.readDoc(hit.documentId)) } }) {
                    Text("${hit.title}  ·  page ${hit.page}", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                    Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
            }
        }
        for ((subject, docs) in s.groups) {
            item(key = "label-$subject") { SectionLabel(subject) }
            items(docs, key = { it.id }) { doc ->
                DocumentCard(doc, s.subjects, vm, onOpen = { nav.navigate(Routes.readDoc(doc.id)) })
            }
        }
    }
}

@Composable
internal fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Cc.colors.primaryTint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Cc.colors.onPrimaryTint else Cc.colors.muted,
        )
    }
}

/** A short message strip. Optional dismiss (x) and one action button. */
@Composable
internal fun Notice(
    text: String,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
        }
    }
}

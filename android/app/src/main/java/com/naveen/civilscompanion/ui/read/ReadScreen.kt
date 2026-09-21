package com.naveen.civilscompanion.ui.read

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.reader.statusLook
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.capture.CaptureTarget
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.EmptyState
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.library.Notice
import com.naveen.civilscompanion.ui.nav.Routes

/** Read (spec 6.3): outline on the left, big text with the sentence highlighted, and the player along the bottom. */
@Composable
fun ReadScreen(nav: NavHostController, docId: String, vm: ReadViewModel = hiltViewModel()) {
    LaunchedEffect(docId) { vm.load(docId) }
    DisposableEffect(docId) { onDispose { vm.leave() } }
    val s by vm.state.collectAsStateWithLifecycle()
    val doc = s.doc
    if (doc == null || !s.loaded) {
        EmptyState("Opening...", "If this stays here, the document may have been removed. Go back to the Library.")
        return
    }
    if (isCompact()) {
        CompactReader(s, vm, nav)
        return
    }
    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        Outline(s, vm, nav, Modifier.width(280.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Toolbar(s, vm, nav)
            s.message?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Notice(it, onDismiss = vm::dismissMessage) } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    s.showOriginal -> OriginalPage(s)
                    s.text.isBlank() -> BlankPage(s, vm)
                    else -> PageText(s, onTap = vm::tapSentence, modifier = Modifier.fillMaxSize())
                }
            }
            if (!s.showOriginal) {
                SelectionBar(s, vm, onAsk = { if (vm.prepareAsk(true)) nav.navigate(Routes.ASK) })
            }
            ReadPlayer(s, vm)
        }
    }
}

/** Upright tablet: the page fills the width; the page list opens on its own (button "Pages") instead of sitting on the left. */
@Composable
private fun CompactReader(s: ReadUiState, vm: ReadViewModel, nav: NavHostController) {
    var showPages by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Cc.colors.background)) {
        Toolbar(s, vm, nav, onPages = { showPages = !showPages }, pagesOpen = showPages)
        s.message?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Notice(it, onDismiss = vm::dismissMessage) } }
        if (showPages) {
            Outline(s, vm, nav, Modifier.weight(1f).fillMaxWidth(), onPicked = { showPages = false })
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    s.showOriginal -> OriginalPage(s)
                    s.text.isBlank() -> BlankPage(s, vm)
                    else -> PageText(s, onTap = vm::tapSentence, modifier = Modifier.fillMaxSize())
                }
            }
            if (!s.showOriginal) {
                SelectionBar(s, vm, onAsk = { if (vm.prepareAsk(true)) nav.navigate(Routes.ASK) })
            }
        }
        ReadPlayer(s, vm)
    }
}

@Composable
private fun Outline(s: ReadUiState, vm: ReadViewModel, nav: NavHostController, modifier: Modifier, onPicked: () -> Unit = {}) {
    val doc = s.doc ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(s.index, s.pages.size) {
        if (s.pages.isNotEmpty()) listState.animateScrollToItem(s.index.coerceIn(0, s.pages.lastIndex))
    }
    val scanLike = doc.type == "scan" || doc.type == "image"
    Column(
        modifier.background(Cc.colors.rail).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(doc.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, color = Cc.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            "${maxOf(doc.pages, s.pages.size)} pages · ${statusLook(doc.processingStatus, doc.statusDetail).label}",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
        BigButton(
            if (s.showOriginal) "Show the text" else if (scanLike) "Show the photo" else "Open PDF",
            onClick = vm::toggleOriginal, filled = false, modifier = Modifier.fillMaxWidth(),
        )
        BigButton(
            "Scan a book page",
            onClick = {
                CaptureTarget.documentId = if (scanLike) doc.id else null
                nav.navigate(Routes.CAPTURE)
            },
            filled = false, modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Pages")
        if (s.pages.isEmpty()) {
            Text(
                "The pages are still arriving from the server. They appear after the next sync.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(s.pages, key = { _, p -> p.id }) { i, p ->
                val selected = i == s.index
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(if (selected) Cc.colors.primaryTint else Cc.colors.rail)
                        .clickable { vm.openPage(i); onPicked() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Page ${p.page}", style = MaterialTheme.typography.labelMedium, color = if (selected) Cc.colors.onPrimaryTint else Cc.colors.ink)
                    Text(
                        p.text.trim().replace(Regex("\\s+"), " ").take(48).ifBlank { "(no text yet)" },
                        style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(s: ReadUiState, vm: ReadViewModel, nav: NavHostController, onPages: (() -> Unit)? = null, pagesOpen: Boolean = false) {
    val total = maxOf(s.doc?.pages ?: 0, s.pages.size)
    if (onPages != null) {
        // Upright tablet: two short rows so every button is visible without sideways scrolling.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(if (pagesOpen) "Close pages" else "Pages", onClick = onPages, filled = false)
                BigButton("Previous", onClick = vm::previousPage, filled = false, enabled = s.index > 0)
                Text("Page ${s.pageNumber} of $total", style = MaterialTheme.typography.labelLarge, color = Cc.colors.ink)
                BigButton("Next", onClick = vm::nextPage, filled = false, enabled = s.index < s.pages.lastIndex)
                BigButton(if (s.speaking) "Pause" else "Read aloud", onClick = vm::toggleReading, enabled = s.sentences.isNotEmpty())
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("Ask about this page", onClick = { if (vm.prepareAsk(false)) nav.navigate(Routes.ASK) }, filled = false)
                BigButton("Highlight", onClick = { vm.highlight("point") }, filled = false)
                BigButton("Make notes", onClick = vm::makeNotes, filled = false)
                BigButton("Add to revision", onClick = { vm.highlight("card") }, filled = false)
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigButton("Previous", onClick = vm::previousPage, filled = false, enabled = s.index > 0)
        Text("Page ${s.pageNumber} of $total", style = MaterialTheme.typography.labelLarge, color = Cc.colors.ink)
        BigButton("Next", onClick = vm::nextPage, filled = false, enabled = s.index < s.pages.lastIndex)
        BigButton(if (s.speaking) "Pause" else "Read aloud", onClick = vm::toggleReading, enabled = s.sentences.isNotEmpty())
        BigButton("Ask about this page", onClick = { if (vm.prepareAsk(false)) nav.navigate(Routes.ASK) }, filled = false)
        BigButton("Highlight", onClick = { vm.highlight("point") }, filled = false)
        BigButton("Make notes", onClick = vm::makeNotes, filled = false)
        BigButton("Add to revision", onClick = { vm.highlight("card") }, filled = false)
    }
}

@Composable
private fun BlankPage(s: ReadUiState, vm: ReadViewModel) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This page has no text yet", style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
        Text(
            if (s.pages.isEmpty()) "The pages are still arriving. Check again after the next sync."
            else "It may be a picture or a scan. The tablet can read English pages by itself. Telugu pages need the server's AI, which works when you are online.",
            style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted,
        )
        if (s.busy) CircularProgressIndicator()
        if (s.pages.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Read this page on the tablet", onClick = vm::readOnDevice, enabled = !s.busy)
                BigButton("Read with AI", onClick = vm::readWithAi, filled = false)
                BigButton("Show the original", onClick = vm::toggleOriginal, filled = false)
            }
        }
    }
}

@Composable
private fun OriginalPage(s: ReadUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val bitmap = s.original
        when {
            bitmap != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                CcCard(Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Original page ${s.pageNumber}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            s.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Text(
                "The original page is not available. It needs the internet the first time.",
                style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted, modifier = Modifier.padding(40.dp),
            )
        }
    }
}

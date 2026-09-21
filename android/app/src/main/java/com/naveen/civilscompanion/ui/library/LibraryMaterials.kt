package com.naveen.civilscompanion.ui.library

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.reader.statusLook
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** "Recommended" tab: free official material, optional books, and the library-day panel on the right. */
@Composable
internal fun RecommendedPane(s: LibraryUiState, vm: LibraryViewModel, nav: NavHostController) {
    if (isCompact()) {
        // Upright tablet: one scrolling list, the library-day panel at the end.
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            recommendedItems(s, vm, nav)
            item(key = "library-day-panel") { LibraryDayPanel(s, vm, nav, Modifier.fillMaxWidth(), scrollable = false) }
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            recommendedItems(s, vm, nav)
        }
        LibraryDayPanel(s, vm, nav, Modifier.width(340.dp).fillMaxHeight())
    }
}

private fun LazyListScope.recommendedItems(s: LibraryUiState, vm: LibraryViewModel, nav: NavHostController) {
    item(key = "official-label") { SectionLabel("Free official material") }
    if (s.official.isEmpty()) {
        item(key = "official-empty") {
            Text(
                "The list arrives from the server with the next sync. Connect to the internet and check again in a minute.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
            )
        }
    }
    items(s.official, key = { it.material.id }) { row -> OfficialCard(row, vm, nav) }
    if (s.books.isNotEmpty()) {
        item(key = "books-label") { SectionLabel("Standard books · optional") }
        item(key = "books-note") {
            Text(
                "These are printed books. Your plan never depends on them. Add one to the library-day list if you want to read it in a library.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
        items(s.books, key = { it.material.id }) { row -> BookCard(row, vm) }
    }
}

@Composable
private fun OfficialCard(row: MaterialRow, vm: LibraryViewModel, nav: NavHostController) {
    val m = row.material
    val doc = row.document
    val uri = LocalUriHandler.current
    CcCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                if (m.why.isNotBlank()) Text(m.why, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.neededFor.isNotBlank()) Pill("Needed for: ${m.neededFor}", tone = 1)
                    if (m.subject.isNotBlank()) Pill(m.subject)
                    if (doc != null) {
                        val look = statusLook(doc.processingStatus, doc.statusDetail)
                        Pill(look.label, tone = look.tone)
                    }
                }
            }
            when {
                doc != null && doc.processingStatus == "processed" -> BigButton("Open", onClick = { nav.navigate(Routes.readDoc(doc.id)) })
                doc != null && doc.processingStatus == "failed" -> BigButton("Try again", onClick = { vm.retry(doc) }, filled = false)
                doc != null -> Text("Please wait", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                m.url.endsWith(".pdf", ignoreCase = true) -> BigButton("Download", onClick = { vm.download(m) })
                m.url.isNotBlank() -> BigButton("Open website", onClick = {
                    try {
                        uri.openUri(m.url)
                    } catch (e: ActivityNotFoundException) {
                        vm.tell("No web browser was found on this tablet.")
                    } catch (e: IllegalArgumentException) {
                        vm.tell("This link cannot be opened.")
                    }
                }, filled = false)
                else -> Text("Link not confirmed yet", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
        }
    }
}

@Composable
private fun BookCard(row: MaterialRow, vm: LibraryViewModel) {
    val m = row.material
    val onList = row.onList
    CcCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                if (m.why.isNotBlank()) Text(m.why, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                if (m.neededFor.isNotBlank()) Pill("Helps with: ${m.neededFor}")
            }
            if (onList == null) {
                BigButton("Add to library-day list", onClick = { vm.addToLibraryList(m) }, filled = false)
            } else {
                Pill("On your list", tone = 1)
                TextButton(onClick = { vm.removeFromLibraryList(onList) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            }
        }
    }
}

/** "Library day · optional": next visit, the books to read, and a tip. */
@Composable
private fun LibraryDayPanel(s: LibraryUiState, vm: LibraryViewModel, nav: NavHostController, modifier: Modifier, scrollable: Boolean = true) {
    CcCard(if (scrollable) modifier.verticalScroll(rememberScrollState()) else modifier) {
        SectionLabel("Library day · optional")
        val visit = if (s.libraryDay.enabled) libraryDayLabel(s.libraryDay.date) else "Not planned"
        Text("Next visit: $visit", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        TextButton(onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Set the day in Settings")
        }
        if (s.libraryList.isEmpty()) {
            Text(
                "Books you add with \"Add to library-day list\" appear here.",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
            )
        }
        for (item in s.libraryList) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = item.done, onCheckedChange = { vm.toggleDone(item) })
                Text(item.book, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f).padding(end = 4.dp))
            }
        }
        Text(
            "Tip: in the library, photograph the key pages with \"Scan with camera\". They become searchable and can be read aloud.",
            style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
        )
        BigButton("Scan with camera", onClick = { nav.navigate(Routes.CAPTURE) }, filled = false, modifier = Modifier.fillMaxWidth())
    }
}

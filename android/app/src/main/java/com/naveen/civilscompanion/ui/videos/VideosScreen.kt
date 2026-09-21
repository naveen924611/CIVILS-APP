package com.naveen.civilscompanion.ui.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.Video
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.nav.Routes

/** Videos (spec 6.10): everything saved, paste a link, search. */
@Composable
fun VideosScreen(nav: NavHostController, vm: VideosViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.setTopic(null) }
    val compact = isCompact()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 20.dp else 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            "Videos",
            subtitle = "Saved YouTube videos. Notes at a time in the video work offline.",
            actions = { BigButton("Focus timer", onClick = { runCatching { nav.navigate(Routes.FOCUS) } }, filled = false) },
        )
        VideosContent(nav, vm)
    }
}

/**
 * The videos of one topic: for a "Videos" tab in Notes or Library (`TopicVideosPanel(nav, topicId)`).
 * Not scrollable by itself, so place it inside a scrolling column.
 */
@Composable
fun TopicVideosPanel(nav: NavHostController, topicId: String, vm: VideosViewModel = hiltViewModel()) {
    LaunchedEffect(topicId) { vm.setTopic(topicId) }
    VideosContent(nav, vm)
}

@Composable
private fun VideosContent(nav: NavHostController, vm: VideosViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val x = ui.extra

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Paste a YouTube link")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    label = { Text("https://www.youtube.com/watch?v=...") },
                    modifier = Modifier.weight(1f),
                )
                BigButton("Add", onClick = { vm.addLink(link); link = "" }, enabled = link.isNotBlank() && !x.busy)
            }
            SectionLabel("Search YouTube")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(if (vm.topicId != null) "Leave empty to search this topic" else "What do you want to learn?") },
                    modifier = Modifier.weight(1f),
                )
                BigButton("Search", onClick = { vm.search(query) }, filled = false, enabled = !x.busy && (query.isNotBlank() || vm.topicId != null))
            }
            if (x.busy) Text("Please wait…", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            x.message?.let { msg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
            }
        }

        if (x.results.isNotEmpty()) {
            CcCard(Modifier.fillMaxWidth()) {
                SectionLabel("Search results")
                x.results.forEach { hit ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(hit.title, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                            Text(hit.channel, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
                        }
                        BigButton("Save", onClick = { vm.addLink(hit.youtubeId) }, filled = false, enabled = !x.busy)
                    }
                }
                TextButton(onClick = vm::clearResults, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear results") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "todo" to "To watch", "watched" to "Watched").forEach { (id, label) ->
                FilterChip(
                    selected = x.filter == id, onClick = { vm.setFilter(id) }, label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (ui.videos.isEmpty()) {
            Text(
                if (x.filter == "all") "No videos yet. Paste a link above, or search." else "Nothing in this list.",
                style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted,
            )
        }
        ui.videos.take(200).forEach { video -> VideoRow(video) { runCatching { nav.navigate(Routes.video(video.id)) } } }
    }
}

@Composable
private fun VideoRow(video: Video, onOpen: () -> Unit) {
    CcCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title.ifBlank { "YouTube video" }, style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                val meta = listOf(video.channel, VideoLogic.durationLabel(video.durationSeconds)).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            }
            if (video.watched) Pill("Watched", tone = 1)
            if (video.embeddable) Pill("Plays in app", tone = 0) else Pill("Opens in YouTube · link saved here", tone = 2)
        }
    }
}

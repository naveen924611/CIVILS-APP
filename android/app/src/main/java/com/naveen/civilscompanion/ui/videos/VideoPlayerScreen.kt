package com.naveen.civilscompanion.ui.videos

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.data.model.Video
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.common.isCompact

/** One video (spec 6.10): official YouTube player, "+ Note at mm:ss", notes you can tap to jump, Open in YouTube. */
@Composable
fun VideoPlayerScreen(nav: NavHostController, videoId: String, vm: VideoPlayerViewModel = hiltViewModel()) {
    LaunchedEffect(videoId) { vm.open(videoId) }
    val video by vm.video.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val controller = remember(videoId) { PlayerController() }
    val context = LocalContext.current
    val current = video

    if (current == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This video is not on this tablet yet.", style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
            Text("It may still be arriving with the next sync.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            BigButton("Back to Videos", onClick = { nav.popBackStack() }, filled = false)
        }
        return
    }

    fun openInYouTube() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VideoLogic.watchUrl(current.youtubeId, controller.seconds)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val compact = isCompact()
    val mainPane: @Composable () -> Unit = {
        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Videos") }
        Text(current.title.ifBlank { "YouTube video" }, style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
        HeaderLine(current)
        if (current.embeddable) {
            YouTubePlayer(
                youtubeId = current.youtubeId,
                controller = controller,
                onBlocked = vm::markNotEmbeddable,
                onEnded = { vm.markWatched(true) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            if (controller.errorCode != 0) {
                Text(VideoLogic.errorText(controller.errorCode), style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onDangerTint)
            }
            Text(
                "The video needs internet. Your notes below work offline.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        } else {
            CcCard(Modifier.fillMaxWidth()) {
                Text("This video opens in YouTube", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    "Its owner does not allow playing it inside other apps. The link is saved here, and you can still write notes with times.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Open in YouTube", onClick = ::openInYouTube, filled = !current.embeddable)
            BigButton(if (current.watched) "Mark as not watched" else "Mark as watched", onClick = { vm.markWatched(!current.watched) }, filled = false)
            BigButton("Refresh details", onClick = vm::refreshDetails, filled = false)
        }
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
            }
        }
        SummaryCard(summary, vm)
    }

    if (compact) {
        // upright tablet: the video and its details first, the notes below, all in one scrolling column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            mainPane()
            NotesPanel(controller, notes, vm, current.embeddable)
        }
    } else {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                mainPane()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NotesPanel(controller, notes, vm, current.embeddable)
            }
        }
    }
}

@Composable
private fun HeaderLine(video: Video) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val meta = listOf(video.channel, VideoLogic.durationLabel(video.durationSeconds)).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
        if (video.embeddable) Pill("Plays in app") else Pill("Opens in YouTube · link saved here", tone = 2)
        if (video.watched) Pill("Watched", tone = 1)
    }
}

@Composable
private fun SummaryCard(summary: SummaryUi, vm: VideoPlayerViewModel) {
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("What to listen for")
        if (summary.text.isNotBlank()) {
            Text(summary.text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink)
            if (summary.note.isNotBlank()) Text(summary.note, style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        } else {
            Text(
                "Ask for a few lines about what this video is probably about. It is made from the title and your notes, not from the video.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
        summary.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cc.colors.onDangerTint) }
        if (summary.asking) {
            Text("Asking… (it waits for internet if you are offline)", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
        } else {
            BigButton(if (summary.text.isBlank()) "Make it" else "Make it again", onClick = vm::askSummary, filled = false)
        }
    }
}

@Composable
private fun NotesPanel(
    controller: PlayerController,
    notes: List<com.naveen.civilscompanion.data.model.VideoNote>,
    vm: VideoPlayerViewModel,
    canPlay: Boolean,
) {
    var text by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    CcCard(Modifier.fillMaxWidth()) {
        SectionLabel("Notes with time")
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("What is said here?") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (canPlay) {
            BigButton(
                "+ Note at ${VideoLogic.formatTime(controller.seconds)}",
                onClick = { vm.addNote(controller.seconds, text); text = "" },
                enabled = text.isNotBlank(),
            )
        } else {
            Text("The time in the video is not known here. Type the time yourself (for example 12:30).", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
            OutlinedTextField(
                value = manual, onValueChange = { manual = it }, singleLine = true, label = { Text("Time, like 12:30") },
                modifier = Modifier.fillMaxWidth(),
            )
            val parsed = VideoLogic.parseTime(manual)
            BigButton(
                "+ Note at ${VideoLogic.formatTime(parsed ?: 0)}",
                onClick = { vm.addNote(parsed ?: 0, text); text = "" },
                enabled = text.isNotBlank() && parsed != null,
            )
        }
    }
    if (notes.isEmpty()) {
        Text("No notes yet. They are saved with the topic and work offline.", style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted)
    }
    notes.forEach { note ->
        CcCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    VideoLogic.formatTime(note.seconds),
                    style = MaterialTheme.typography.titleMedium, color = Cc.colors.primary,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = canPlay) { controller.seekTo(note.seconds) },
                )
                Text(note.text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.deleteNote(note.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }
}

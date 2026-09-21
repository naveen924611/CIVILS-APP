package com.naveen.civilscompanion.ui.answers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.ask.ActionText
import com.naveen.civilscompanion.ui.ask.ChipButton
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.common.rememberCameraPermission
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.util.CaptureFiles
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One answer: the question, a timer, writing (photos of paper or typed) and, later, the evaluation. */
@Composable
fun AnswerScreen(nav: NavHostController, answerId: String, vm: AnswerViewModel = hiltViewModel()) {
    val answer by vm.answer.collectAsStateWithLifecycle()
    val uploadPending by vm.uploadPending.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(answerId) { vm.load(answerId) }

    val compact = isCompact()
    Column(
        Modifier.fillMaxSize().background(Cc.colors.background).verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 24.dp).padding(bottom = if (compact) 80.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(
            "Answer writing",
            subtitle = "Write on paper and take a photo, or type it here.",
            actions = { ActionText("All answers", onClick = { nav.navigate(Routes.ANSWERS) }) },
        )
        notice?.let { msg ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cc.colors.accentTint).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.onAccentTint, modifier = Modifier.weight(1f))
                ActionText("OK", onClick = vm::clearNotice)
            }
        }
        val a = answer
        if (a == null) {
            Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
            return@Column
        }
        QuestionCard(a.question, a.wordLimit, a.kind, a.score)
        when (a.status) {
            "draft" -> WritePane(vm, a.wordLimit)
            "done" -> {
                val feedback = AnswerLogic.parseFeedback(a.feedback)
                if (feedback != null) {
                    AnswerFeedbackView(feedback, a.score, a.wordLimit)
                } else {
                    Text("The feedback is not here yet. It appears after the next sync.", style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
                BigButton("Back to all answers", onClick = { nav.navigate(Routes.ANSWERS) }, filled = false)
            }
            "failed" -> CcCard(Modifier.fillMaxWidth()) {
                Text("This answer could not be checked", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    "The photos may not have reached the server, or the AI was busy. You can ask again, or write it again.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Ask again", onClick = vm::checkAgain)
                    BigButton("Write it again", onClick = vm::writeAgain, filled = false)
                }
            }
            else -> CcCard(Modifier.fillMaxWidth()) {
                Text("Sent for feedback", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
                Text(
                    if (uploadPending) "Your photos are waiting for the internet. They are sent by themselves when it is on. Keep the app open."
                    else "The feedback arrives in a few minutes. You will get a notification. You can leave this page.",
                    style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
                )
                if (uploadPending) Pill("Waiting for internet", tone = 2)
            }
        }
    }
}

@Composable
private fun QuestionCard(question: String, wordLimit: Int, kind: String, score: Double?) {
    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(AnswerLogic.kindLabel(kind))
            Pill("$wordLimit words")
            if (score != null) Pill(AnswerLogic.scoreLabel(score), tone = AnswerLogic.scoreTone(score))
        }
        Text(question, style = MaterialTheme.typography.headlineSmall, color = Cc.colors.ink)
    }
}

@Composable
private fun WritePane(vm: AnswerViewModel, wordLimit: Int) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val typed by vm.typed.collectAsStateWithLifecycle()
    val totalSec = AnswerLogic.suggestedMinutes(wordLimit) * 60
    val left = totalSec - timer.elapsedSec

    CcCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                AnswerLogic.clock(left), style = MaterialTheme.typography.displaySmall,
                color = if (left < 0) Cc.colors.onDangerTint else Cc.colors.ink,
            )
            Text(
                if (left < 0) "over the suggested time" else "left of ${AnswerLogic.suggestedMinutes(wordLimit)} minutes",
                style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
            )
            BigButton(if (timer.running) "Pause" else if (timer.elapsedSec == 0) "Start timer" else "Continue", onClick = vm::toggleTimer)
            if (timer.elapsedSec > 0) BigButton("Reset", onClick = vm::resetTimer, filled = false)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Written on paper", selected = tab == 0, onClick = { vm.setTab(0) })
        ChipButton("Type it here", selected = tab == 1, onClick = { vm.setTab(1) })
    }
    if (tab == 0) PhotoPane(vm, photos) else TypedPane(vm, typed, wordLimit)
    BigButton(
        "Send for feedback",
        onClick = vm::submit,
        enabled = if (tab == 0) photos.isNotEmpty() else AnswerLogic.canSendTyped(typed),
    )
}

@Composable
private fun PhotoPane(vm: AnswerViewModel, photos: List<File>) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        if (ok && file != null) vm.addPhotoFile(file)
        pending = null
    }
    val openCamera = rememberCameraPermission(onDenied = vm::micOrCameraDenied) {
        val (file, uri) = CaptureFiles.newPhoto(context, "answer")
        pending = file
        takePicture.launch(uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.addPhotoUri(uri)
    }
    CcCard(Modifier.fillMaxWidth()) {
        Text(
            "Take one photo for each page, in order. Put the page flat in good light.",
            style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted,
        )
        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(photos, key = { _, f -> f.name }) { index, file ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PhotoThumb(file, index + 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, color = Cc.colors.ink)
                            ActionText("Remove", onClick = { vm.removePhoto(file) }, danger = true)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (photos.isEmpty()) "Take a photo" else "Add another page", onClick = openCamera)
            BigButton("Choose from gallery", onClick = { pick.launch("image/*") }, filled = false)
        }
    }
}

@Composable
private fun TypedPane(vm: AnswerViewModel, typed: String, wordLimit: Int) {
    val words = AnswerLogic.wordCount(typed)
    CcCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = typed,
            onValueChange = vm::setTyped,
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            placeholder = { Text("Write your answer here") },
            minLines = 10,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("$words of $wordLimit words", tone = if (words == 0) 0 else AnswerLogic.lengthTone(words, wordLimit))
            Text("Saved on this tablet as you type.", style = MaterialTheme.typography.labelMedium, color = Cc.colors.muted)
        }
    }
}

@Composable
private fun PhotoThumb(file: File, page: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { decodeThumb(file) }
    }
    Box(
        Modifier.size(width = 150.dp, height = 200.dp).clip(RoundedCornerShape(10.dp)).background(Cc.colors.rail),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = "Page $page", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("...", color = Cc.colors.muted)
        }
    }
}

/** A small preview of a photo, turned the right way up. */
private fun decodeThumb(file: File): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 700) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        upright.asImageBitmap()
    } catch (e: IOException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

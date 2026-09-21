package com.naveen.civilscompanion.ui.setup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naveen.civilscompanion.theme.Cc
import java.util.Locale

/**
 * The phone part of first-run setup (spec 6.19): notifications, exact alarms, battery, offline voice, microphone,
 * camera and Do Not Disturb access. Each card shows its current state; every one can be skipped and done later.
 * Shown inside the setup wizard (SetupScreen.kt), which supplies the scrolling.
 */
@Composable
fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val colors = Cc.colors

    // Bumped every time the owner comes back from an Android settings page, so the ticks refresh.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceCheck(refresh)

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val micOn = remember(refresh) { has(context, Manifest.permission.RECORD_AUDIO) }
    val cameraOn = remember(refresh) { has(context, Manifest.permission.CAMERA) }
    val dndOn = remember(refresh) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
    val notificationsOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactOn = remember(refresh) { canScheduleExact(context) }
    val batteryOn = remember(refresh) { ignoringBattery(context) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        run {
            Text("This tablet", style = MaterialTheme.typography.displaySmall, color = colors.ink)
            Text(
                "A few quick steps so your briefs arrive on time and play with the screen off. " +
                    "You can skip any step and come back from Settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
            )

            StepCard(
                title = "1. Notifications",
                text = "Lets the app tell you when a brief is ready.",
                done = notificationsOn,
                actionLabel = "Allow notifications",
            ) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startSafely(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }
            StepCard(
                title = "2. Exact alarms",
                text = "Lets the tablet wake at your brief time even if the message from the server was missed.",
                done = exactOn,
                actionLabel = "Allow exact alarms",
            ) {
                if (Build.VERSION.SDK_INT >= 31) {
                    context.startSafely(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                    )
                }
            }
            StepCard(
                title = "3. Battery",
                text = "Choose “Allow” so Android does not stop the app in the background. This keeps briefs and audio reliable.",
                done = batteryOn,
                actionLabel = "Turn off battery limits",
            ) {
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                if (!context.startSafely(ask)) context.startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            StepCard(
                title = "4. Offline English (India) voice",
                text = when (voice.ready) {
                    true -> "The voice is on this tablet. Reading aloud will work without internet."
                    false -> "Download the English (India) voice so reading aloud works without internet."
                    null -> "Checking the voice…"
                },
                done = voice.ready == true,
                actionLabel = "Download voice",
                extraLabel = "Test voice",
                onExtra = { voice.speak("This is the offline English India voice.") },
            ) {
                val ok = context.startSafely(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) ||
                    context.startSafely(Intent("com.android.settings.TTS_SETTINGS"))
                if (!ok) voiceMessage = "Could not open the voice page. Open Android Settings, then Language, then Text-to-speech."
            }
            voiceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onAccentTint) }

            StepCard(
                title = "5. Microphone",
                text = "Lets you ask questions and answer flashcards by voice.",
                done = micOn,
                actionLabel = "Allow microphone",
            ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            StepCard(
                title = "6. Camera",
                text = "Lets you photograph book pages and handwritten answers.",
                done = cameraOn,
                actionLabel = "Allow camera",
            ) { cameraLauncher.launch(Manifest.permission.CAMERA) }
            StepCard(
                title = "7. Do Not Disturb (for Focus)",
                text = "Lets the Focus timer silence notifications while you study. The timer works without it.",
                done = dndOn,
                actionLabel = "Allow Do Not Disturb",
            ) { context.startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }

            Button(
                onClick = onNext,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 52.dp),
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    text: String,
    done: Boolean,
    actionLabel: String,
    extraLabel: String? = null,
    onExtra: () -> Unit = {},
    onAction: () -> Unit,
) {
    val colors = Cc.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, if (done) colors.primary else colors.border, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
            if (done) Text("✓ Done", color = colors.primary, fontWeight = FontWeight.SemiBold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        if (!done || extraLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!done) {
                    Button(onClick = onAction, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(actionLabel)
                    }
                }
                if (extraLabel != null) {
                    OutlinedButton(onClick = onExtra, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(extraLabel)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ checks

private fun Context.startSafely(intent: Intent): Boolean =
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

private fun has(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun canScheduleExact(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun ignoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private class VoiceCheck(val ready: Boolean?, private val engine: TextToSpeech?) {
    fun speak(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "setup-test")
    }
}

/** Starts the phone's speech engine and asks whether an English (India) voice is installed. */
@Composable
private fun rememberVoiceCheck(refresh: Int): VoiceCheck {
    val context = LocalContext.current
    var result by remember(refresh) { mutableStateOf(VoiceCheck(null, null)) }
    DisposableEffect(refresh) {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(context) { status ->
            val engine = holder[0]
            if (status != TextToSpeech.SUCCESS || engine == null) {
                result = VoiceCheck(false, null)
            } else {
                val locale = Locale("en", "IN")
                val offlineVoice = runCatching {
                    engine.voices.orEmpty().any {
                        it.locale.language == "en" && it.locale.country == "IN" && !it.isNetworkConnectionRequired
                    }
                }.getOrDefault(false)
                val available = engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
                if (available) engine.setLanguage(locale)
                result = VoiceCheck(offlineVoice || available, engine)
            }
        }
        onDispose { holder[0]?.shutdown() }
    }
    return result
}

package com.naveen.civilscompanion.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.Pill
import com.naveen.civilscompanion.ui.common.ScreenTitle
import com.naveen.civilscompanion.ui.nav.Routes
import kotlinx.coroutines.delay

/** Focus (spec 6.12): timer with Do Not Disturb, current task, Pause, Finish early, Open material and today's log. */
@Composable
fun FocusScreen(nav: NavHostController, vm: FocusViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.timerState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dndOn by vm.dndOn.collectAsStateWithLifecycle()

    // Check Do Not Disturb access again each time the owner comes back from Android's settings page.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val hasAccess = remember(resumes) { vm.hasDndAccess() }

    // One tick every half second while a phase runs.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = state.phase == FocusPhase.Focus || state.phase == FocusPhase.Break
    LaunchedEffect(running, state.paused) {
        vm.resync()
        while (running) {
            now = System.currentTimeMillis()
            vm.tick(now)
            delay(500)
        }
    }

    var task by remember { mutableStateOf("") }
    var topicId by remember { mutableStateOf<String?>(null) }
    var blockId by remember { mutableStateOf<String?>(null) }
    var style by remember { mutableStateOf(FocusLogic.STYLE_50) }
    var customFocus by remember { mutableIntStateOf(40) }
    var customBreak by remember { mutableIntStateOf(10) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            "Focus",
            subtitle = "One thing at a time. The timer keeps running if you open your notes.",
            actions = {
                BigButton("Videos", onClick = { runCatching { nav.navigate(Routes.VIDEOS) } }, filled = false)
            },
        )
        DndChip(state.phase, dndOn, hasAccess) { runCatching { context.startActivity(vm.dndSettingsIntent()) } }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (state.phase) {
                    FocusPhase.Idle -> StartPanel(
                        task = task, onTask = { task = it; vm.searchTopics(it); topicId = null; blockId = null },
                        style = style, onStyle = { style = it },
                        customFocus = customFocus, customBreak = customBreak,
                        onCustom = { f, b -> customFocus = f.coerceIn(FocusLogic.MIN_MINUTES, FocusLogic.MAX_MINUTES); customBreak = b.coerceIn(0, 60) },
                        ui = ui,
                        onPickTask = { block -> task = block.title; topicId = block.topicId; blockId = block.id; vm.searchTopics("") },
                        onPickTopic = { topic -> task = topic.title; topicId = topic.id; blockId = null; vm.searchTopics("") },
                        onStart = {
                            val (f, b) = FocusLogic.lengths(style, customFocus, customBreak)
                            vm.start(style, f, b, task, topicId, blockId)
                        },
                    )
                    FocusPhase.Asking -> AskProgress(onAnswer = vm::answer)
                    FocusPhase.Focus, FocusPhase.Break -> RunningPanel(state, now, vm, nav)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TodayLog(ui.todayMinutes, ui.sessions)
            }
        }
    }
}

@Composable
private fun DndChip(phase: FocusPhase, dndOn: Boolean, hasAccess: Boolean, onAllow: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            phase == FocusPhase.Focus && dndOn -> Pill("Do Not Disturb is on", tone = 1)
            hasAccess -> Pill("Do Not Disturb will switch on when you start", tone = 0)
            else -> {
                Pill("Do Not Disturb is off", tone = 2)
                Text(
                    "Allow it to silence notifications during a session. The timer works without it.",
                    style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun StartPanel(
    task: String,
    onTask: (String) -> Unit,
    style: String,
    onStyle: (String) -> Unit,
    customFocus: Int,
    customBreak: Int,
    onCustom: (Int, Int) -> Unit,
    ui: FocusUi,
    onPickTask: (com.naveen.civilscompanion.ui.today.PlanBlock) -> Unit,
    onPickTopic: (com.naveen.civilscompanion.data.model.Topic) -> Unit,
    onStart: () -> Unit,
) {
    CcCard(Modifier.fillMaxWidth()) {
        Text("What are you working on?", style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink)
        OutlinedTextField(
            value = task, onValueChange = onTask, singleLine = true,
            label = { Text("A task, or start typing a topic") },
            modifier = Modifier.fillMaxWidth(),
        )
        TaskChoices(ui.tasks, ui.topicHits, onPickTask, onPickTopic)
        StylePicker(style, onStyle, customFocus, customBreak, onCustom)
        val (f, b) = FocusLogic.lengths(style, customFocus, customBreak)
        BigButton("Start $f minutes of focus" + if (b > 0) ", then a $b minute break" else "", onClick = onStart)
    }
}

@Composable
private fun RunningPanel(state: FocusState, now: Long, vm: FocusViewModel, nav: NavHostController) {
    val onBreak = state.phase == FocusPhase.Break
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!onBreak) {
            Text(
                state.task.ifBlank { "Focus session" },
                style = MaterialTheme.typography.titleLarge, color = Cc.colors.ink,
            )
        }
        TimerCircle(
            progress = FocusLogic.progress(state, now),
            clock = FocusLogic.clock(FocusLogic.remainingMs(state, now)),
            label = when {
                state.paused -> "Paused"
                onBreak -> "Break"
                else -> "Focus"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.paused) BigButton("Resume", onClick = vm::resume) else BigButton("Pause", onClick = vm::pause, filled = false)
            if (onBreak) {
                BigButton("Skip break", onClick = vm::skipBreak)
            } else {
                BigButton("Finish early", onClick = vm::finishEarly)
                BigButton(
                    "Open material",
                    onClick = {
                        val id = state.topicId
                        runCatching { nav.navigate(if (id != null) Routes.noteTopic(id) else Routes.LIBRARY) }
                    },
                    filled = false,
                )
            }
        }
        if (!onBreak) TextButton(onClick = vm::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel without saving") }
    }
}

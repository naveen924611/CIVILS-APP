package com.naveen.civilscompanion.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.CcCard
import com.naveen.civilscompanion.ui.common.SectionLabel
import com.naveen.civilscompanion.ui.voice.VoiceKeys

private val VOICES = listOf("en-IN" to "English (India)", "en-GB" to "English (UK)", "en-US" to "English (US)", "te-IN" to "Telugu")
private val LISTEN = listOf("en-IN" to "English (India)", "te-IN" to "Telugu")
private val SPEEDS = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)

/** Shown inside Settings under "Voice and reading": reading voice, speed, listening language, hands-free, headphones. */
@Composable
fun AskSettingsSection(nav: NavHostController, vm: AskSettingsViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val listen by vm.listen.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val onHeadphones by vm.speakOnHeadphones.collectAsStateWithLifecycle()
    val handsFree by vm.handsFree.collectAsStateWithLifecycle()
    val wake by vm.wakePhrase.collectAsStateWithLifecycle()
    val headphones by vm.headphoneMap.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Reading voice")
            ChoiceRow(VOICES, selected = name, onPick = vm::setName)
            SectionLabel("Reading speed")
            ChoiceRow(SPEEDS.map { it.toString() to "${it}x" }, selected = speed.toString(), onPick = { vm.setSpeed(it.toFloat()) })
            ActionText("Hear a sample", onClick = vm::testVoice)
            SectionLabel("When you speak to the tablet, it listens for")
            ChoiceRow(LISTEN, selected = listen, onPick = vm::setListen)
            Text(
                "Offline listening needs the offline speech pack, which the setup steps help you install.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
        CcCard(Modifier.fillMaxWidth()) {
            SwitchRow("Read new answers aloud when headphones are connected", onHeadphones, vm::setSpeakOnHeadphones)
            SwitchRow("Hands-free revision: the tablet reads the card and you say again, hard, good or easy", handsFree, vm::setHandsFree)
            SwitchRow("Wake phrase (only while the app is open, uses more battery)", wake, vm::setWakePhrase)
        }
        CcCard(Modifier.fillMaxWidth()) {
            SectionLabel("Headphone buttons")
            VoiceKeys.HEADPHONE_GESTURES.forEach { (gesture, label) ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = Cc.colors.ink)
                ChoiceRow(
                    VoiceKeys.HEADPHONE_ACTIONS,
                    selected = headphones[gesture].orEmpty(),
                    onPick = { vm.setHeadphone(gesture, it) },
                )
            }
            Text(
                "Long press is often used by the phone's own assistant, so it is left alone.",
                style = MaterialTheme.typography.bodySmall, color = Cc.colors.muted,
            )
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (code, label) -> ChipButton(label, selected = selected == code, onClick = { onPick(code) }) }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

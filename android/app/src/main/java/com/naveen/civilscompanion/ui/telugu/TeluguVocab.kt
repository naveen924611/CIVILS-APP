package com.naveen.civilscompanion.ui.telugu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.data.model.TeluguItem
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard

/** Flash cards: Telugu word first (with sound), then the meaning. "I knew it" or "Not yet" is saved as progress. */
@Composable
fun VocabSession(cards: List<TeluguItem>, vm: TeluguViewModel, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var knew by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Back", { vm.stopSpeaking(); onClose() }, filled = false)
            Text(
                if (index < cards.size) "Word ${index + 1} of ${cards.size}" else "All done",
                style = MaterialTheme.typography.titleMedium, color = Cc.colors.ink,
            )
        }
        LinearProgressIndicator(
            progress = { if (cards.isEmpty()) 1f else index.toFloat() / cards.size },
            modifier = Modifier.fillMaxWidth(),
            color = Cc.colors.primary,
            trackColor = Cc.colors.rail,
        )
        if (index >= cards.size) {
            CcCard(Modifier.fillMaxWidth()) {
                Text("Finished", style = MaterialTheme.typography.headlineMedium, color = Cc.colors.ink)
                Text(
                    "You knew $knew of ${cards.size} words. The ones you did not know will come back soon.",
                    style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted,
                )
                BigButton("Back to today", onClose)
            }
            return@Column
        }
        val item = cards[index]
        val card = TeluguContent.vocab(item.content)
        CcCard(Modifier.fillMaxWidth()) {
            TeluguText(card.te, size = 40.sp, bold = true)
            if (card.roman.isNotBlank()) Text(card.roman, style = MaterialTheme.typography.bodyLarge, color = Cc.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HearButton({ vm.speak(card.te) }, label = "Hear the word")
                if (card.exTe.isNotBlank()) HearButton({ vm.speak(card.exTe) }, label = "Hear a sentence")
            }
            if (revealed) {
                Text(card.en, style = MaterialTheme.typography.headlineSmall, color = Cc.colors.primary)
                if (card.exTe.isNotBlank()) {
                    TeluguText(card.exTe, size = 20.sp)
                    Text(card.exEn, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.muted)
                }
            }
        }
        if (!revealed) {
            BigButton("Show the meaning", { revealed = true }, modifier = Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(
                    "Not yet",
                    { vm.record(item.id, 0.0); revealed = false; index++ },
                    modifier = Modifier.weight(1f), filled = false,
                )
                BigButton(
                    "I knew it",
                    { vm.record(item.id, 1.0); knew++; revealed = false; index++ },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

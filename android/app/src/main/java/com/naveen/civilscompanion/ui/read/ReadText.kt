package com.naveen.civilscompanion.ui.read

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naveen.civilscompanion.reader.SentenceSplitter
import com.naveen.civilscompanion.reader.paragraphIndexAt
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.NotoSansTelugu
import com.naveen.civilscompanion.ui.common.isCompact
import com.naveen.civilscompanion.ui.common.BigButton
import com.naveen.civilscompanion.ui.common.CcCard

/** The page as large text. The sentence being read (or tapped) is tinted; saved highlights have a warm tint. */
@Composable
internal fun PageText(s: ReadUiState, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val tap by rememberUpdatedState(onTap)
    val readingColor = Cc.colors.primaryTint
    val savedColor = Cc.colors.accentTint
    val baseStyle = MaterialTheme.typography.bodyLarge.let {
        val bigger = it.copy(fontSize = 22.sp, lineHeight = 36.sp, color = Cc.colors.ink)
        if (s.telugu) bigger.copy(fontFamily = NotoSansTelugu) else bigger
    }

    // keep the sentence being read in view
    LaunchedEffect(s.current, s.pageNumber, s.speaking) {
        val sentence = s.sentences.getOrNull(s.current)
        if (s.speaking && sentence != null) {
            val at = paragraphIndexAt(s.paragraphs, sentence.start)
            if (at >= 0) listState.animateScrollToItem(at)
        }
    }
    LaunchedEffect(s.pageNumber) { listState.scrollToItem(0) }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (isCompact()) 20.dp else 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(s.paragraphs, key = { i, p -> "${s.pageNumber}-$i-${p.start}" }) { _, paragraph ->
            val slice = s.text.substring(paragraph.start, paragraph.end)
            val annotated = buildAnnotatedString {
                append(slice)
                for ((i, sentence) in s.sentences.withIndex()) {
                    val a = maxOf(sentence.start, paragraph.start) - paragraph.start
                    val b = minOf(sentence.end, paragraph.end) - paragraph.start
                    if (a >= b) continue
                    if (i == s.current) addStyle(SpanStyle(background = readingColor), a, b)
                    else if (i in s.highlighted) addStyle(SpanStyle(background = savedColor), a, b)
                }
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = annotated,
                style = baseStyle,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(s.sentences, paragraph.start) {
                        detectTapGestures { position ->
                            val l = layout
                            if (l != null) {
                                val offset = paragraph.start + l.getOffsetForPosition(position)
                                tap(SentenceSplitter.indexAt(s.sentences, offset))
                            }
                        }
                    },
            )
        }
    }
}

/** Appears when a sentence is tapped: what to do with it. */
@Composable
internal fun SelectionBar(s: ReadUiState, vm: ReadViewModel, onAsk: () -> Unit) {
    val sentence = s.sentences.getOrNull(s.current) ?: return
    CcCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            sentence.text, style = MaterialTheme.typography.bodyMedium, color = Cc.colors.ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Save as point", onClick = { vm.highlight("point") }, filled = false)
            BigButton("Must remember", onClick = { vm.highlight("must") }, filled = false)
            BigButton("Make flashcard", onClick = { vm.highlight("card") }, filled = false)
            BigButton("Explain simply", onClick = onAsk, filled = false)
        }
    }
}

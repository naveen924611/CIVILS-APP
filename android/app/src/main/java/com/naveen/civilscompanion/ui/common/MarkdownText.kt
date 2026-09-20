package com.naveen.civilscompanion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.theme.Cc

/** Turns inline Markdown into styled text. */
@Composable
fun inlineText(src: String): AnnotatedString {
    val codeBg = Cc.colors.primaryTint
    return buildAnnotatedString {
        parseInline(src).forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBg else Color.Unspecified,
            )
            withStyle(style) { append(span.text) }
        }
    }
}

/** Shows Markdown text (notes, sheets, answers). Long text scrolls with the screen that holds it. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, color: Color = Cc.colors.ink) {
    val blocks = parseMarkdown(md)
    val type = MaterialTheme.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineText(block.text),
                    style = when (block.level) {
                        1 -> type.headlineMedium
                        2 -> type.headlineSmall
                        else -> type.titleMedium
                    },
                    color = color,
                    modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 20).dp)) {
                    Text("•", style = type.bodyLarge, color = Cc.colors.primary, modifier = Modifier.width(22.dp))
                    Text(inlineText(block.text), style = type.bodyLarge, color = color)
                }
                is MdBlock.Numbered -> Row {
                    Text("${block.number}.", style = type.bodyLarge, color = Cc.colors.primary, modifier = Modifier.width(30.dp))
                    Text(inlineText(block.text), style = type.bodyLarge, color = color)
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(4.dp).height(24.dp).background(Cc.colors.primary, RoundedCornerShape(2.dp)))
                    Text(
                        inlineText(block.text), style = type.bodyLarge, color = Cc.colors.muted,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                is MdBlock.Para -> Text(inlineText(block.text), style = type.bodyLarge, color = color)
                is MdBlock.Code -> Text(
                    block.text,
                    style = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier.fillMaxWidth().background(Cc.colors.rail, RoundedCornerShape(8.dp)).padding(12.dp),
                )
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Cc.colors.border))
            }
        }
    }
}

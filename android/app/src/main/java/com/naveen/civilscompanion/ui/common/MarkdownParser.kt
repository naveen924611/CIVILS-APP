package com.naveen.civilscompanion.ui.common

/** A small Markdown reader for notes, sheets and answers (headings, lists, quotes, bold, italic, code, links). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Numbered(val number: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

private val headingRe = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRe = Regex("^(\\s*)[-*+•]\\s+(.*)$")
private val numberedRe = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Para(para.toString().trim()))
        para.clear()
    }
    val lines = md.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushPara()
            trimmed.startsWith("```") -> {
                flushPara()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(code.toString().trimEnd()))
            }
            ruleRe.matches(line) -> {
                flushPara()
                blocks.add(MdBlock.Rule)
            }
            headingRe.matches(trimmed) -> {
                flushPara()
                val m = headingRe.matchEntire(trimmed)!!
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }
            bulletRe.matches(line) -> {
                flushPara()
                val m = bulletRe.matchEntire(line)!!
                blocks.add(MdBlock.Bullet(m.groupValues[2].trim(), m.groupValues[1].length / 2))
            }
            numberedRe.matches(line) -> {
                flushPara()
                val m = numberedRe.matchEntire(line)!!
                blocks.add(MdBlock.Numbered(m.groupValues[1].toInt(), m.groupValues[2].trim()))
            }
            trimmed.startsWith(">") -> {
                flushPara()
                blocks.add(MdBlock.Quote(trimmed.removePrefix(">").trim()))
            }
            trimmed.startsWith("|") -> {
                // tables are shown as they are, in a monospace block (one block per run of table lines)
                flushPara()
                val table = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    table.appendLine(lines[i].trim())
                    i++
                }
                i--
                blocks.add(MdBlock.Code(table.toString().trimEnd()))
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flushPara()
    return blocks
}

/** Splits one line of text into styled pieces. Stray * characters (like "5 * 3") stay as they are. */
fun parseInline(src: String): List<Span> {
    val out = ArrayList<Span>()
    val sb = StringBuilder()
    var bold = false
    var italic = false
    fun flush() {
        if (sb.isNotEmpty()) {
            out.add(Span(sb.toString(), bold, italic))
            sb.clear()
        }
    }
    fun opens(at: Int, width: Int) = at + width < src.length && !src[at + width].isWhitespace()
    fun closes(at: Int) = at > 0 && !src[at - 1].isWhitespace()
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            src.startsWith("**", i) && ((!bold && opens(i, 2)) || (bold && closes(i))) -> {
                flush()
                bold = !bold
                i += 2
            }
            c == '*' && ((!italic && opens(i, 1)) || (italic && closes(i))) -> {
                flush()
                italic = !italic
                i += 1
            }
            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    out.add(Span(src.substring(i + 1, end), code = true))
                    i = end + 1
                } else {
                    sb.append(c)
                    i++
                }
            }
            c == '[' -> {
                val close = src.indexOf("](", i + 1)
                val end = if (close > i) src.indexOf(')', close + 2) else -1
                if (close > i && end > close) { // keep only the link text
                    sb.append(src, i + 1, close)
                    i = end + 1
                } else {
                    sb.append(c)
                    i++
                }
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    flush()
    return out
}

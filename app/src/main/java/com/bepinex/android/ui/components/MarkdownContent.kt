package com.bepinex.android.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private sealed class MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class Quote(val children: List<MdBlock>) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<MdListItem>) : MdBlock()
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>) : MdBlock()
    data class Image(val alt: String, val url: String) : MdBlock()
    data object Rule : MdBlock()
}

private data class MdListItem(
    val checked: Boolean?,
    val children: List<MdBlock>
)

private sealed class MdInline {
    data class Text(val text: String) : MdInline()
    data class Bold(val children: List<MdInline>) : MdInline()
    data class Italic(val children: List<MdInline>) : MdInline()
    data class Strike(val children: List<MdInline>) : MdInline()
    data class Mark(val children: List<MdInline>) : MdInline()
    data class Code(val text: String) : MdInline()
    data class Link(val children: List<MdInline>, val url: String) : MdInline()
}

private data class MdPalette(
    val link: Color,
    val codeBg: Color,
    val markBg: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color
)

@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val palette = MdPalette(
        link = MaterialTheme.colorScheme.primary,
        codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        markBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        keyword = MaterialTheme.colorScheme.primary,
        string = MaterialTheme.colorScheme.tertiary,
        comment = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SelectionContainer(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            blocks.forEach { RenderBlock(it, palette) { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } }
        }
    }
}

fun plainTextFromMarkdown(markdown: String): String {
    if (markdown.isBlank()) return ""
    return markdown
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*+]\\s+(\\[[ xX]\\]\\s*)?", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("==([^=]+)=="), "$1")
        .replace(Regex("</?mark>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(\\*\\*|__|\\*|_|~~|`)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

@Composable
private fun RenderBlock(
    block: MdBlock,
    palette: MdPalette,
    onLink: (String) -> Unit
) {
    when (block) {
        is MdBlock.Paragraph -> {
            val style = MaterialTheme.typography.bodyMedium
            Text(
                text = inlineString(block.inlines, palette, onLink),
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = inlineString(block.inlines, palette, onLink),
                style = style.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
        }
        is MdBlock.Code -> MarkdownCodeBlock(block.language, block.code, palette)
        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .height(IntrinsicSize.Min)
                    .padding(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    block.children.forEach { RenderBlock(it, palette, onLink) }
                }
            }
        }
        is MdBlock.ListBlock -> {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (item.checked != null) {
                            TaskMark(checked = item.checked)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            item.children.forEach { RenderBlock(it, palette, onLink) }
                        }
                    }
                }
            }
        }
        is MdBlock.Table -> MarkdownTable(block, palette, onLink)
        is MdBlock.Image -> RemoteMarkdownImage(block.url, block.alt)
        MdBlock.Rule -> {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TaskMark(checked: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    if (checked) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, shape)
        )
    }
}

@Composable
private fun MarkdownCodeBlock(language: String, code: String, palette: MdPalette) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (language.isNotBlank()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = highlightCode(code, language, palette),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    palette: MdPalette,
    onLink: (String) -> Unit
) {
    val colCount = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { it.size } ?: 0
    )
    if (colCount == 0) return
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surface
    val oddBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val cellMin = 96.dp
    val useScroll = colCount > 3

    @Composable
    fun row(cells: List<List<MdInline>>, header: Boolean, striped: Boolean) {
        Row(
            modifier = Modifier
                .then(if (useScroll) Modifier else Modifier.fillMaxWidth())
                .background(when {
                    header -> headerBg
                    striped -> oddBg
                    else -> Color.Transparent
                })
        ) {
            repeat(colCount) { index ->
                val cell = cells.getOrNull(index).orEmpty()
                val cellModifier = if (useScroll) {
                    Modifier.widthIn(min = cellMin, max = 220.dp)
                } else {
                    Modifier.weight(1f)
                }
                Text(
                    text = inlineString(cell, palette, onLink),
                    modifier = cellModifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    val tableBody = @Composable {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, border, RoundedCornerShape(8.dp))
        ) {
            row(table.header, header = true, striped = false)
            table.rows.forEachIndexed { index, cells ->
                HorizontalDivider(color = border, thickness = 1.dp)
                row(cells, header = false, striped = index % 2 == 1)
            }
        }
    }

    Box(modifier = Modifier.padding(vertical = 8.dp)) {
        if (useScroll) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                tableBody()
            }
        } else {
            tableBody()
        }
    }
}

@Composable
private fun RemoteMarkdownImage(url: String, alt: String) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) { loadRemoteBitmap(url) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = alt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth
        )
    }
}

@Composable
private fun inlineString(
    inlines: List<MdInline>,
    palette: MdPalette,
    onLink: (String) -> Unit
) = buildAnnotatedString { appendInlines(inlines, palette, onLink) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    palette: MdPalette,
    onLink: (String) -> Unit
) {
    inlines.forEach { inline ->
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.children, palette, onLink)
            }
            is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, palette, onLink)
            }
            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlines(inline.children, palette, onLink)
            }
            is MdInline.Mark -> withStyle(SpanStyle(background = palette.markBg)) {
                appendInlines(inline.children, palette, onLink)
            }
            is MdInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBg)
            ) { append(inline.text) }
            is MdInline.Link -> withLink(
                LinkAnnotation.Url(
                    url = inline.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = palette.link,
                            textDecoration = TextDecoration.Underline
                        )
                    ),
                    linkInteractionListener = { onLink(inline.url) }
                )
            ) { appendInlines(inline.children, palette, onLink) }
        }
    }
}

private val ListPattern = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(?:\\[([ xX])]\\s+)?(.*)$")
private val TableSep = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
private val HeadingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val RulePattern = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return parseLines(lines, 0, lines.size, 0).first
}

private fun parseLines(
    lines: List<String>,
    start: Int,
    end: Int,
    minIndent: Int
): Pair<List<MdBlock>, Int> {
    val blocks = mutableListOf<MdBlock>()
    var i = start
    while (i < end) {
        val raw = lines[i]
        val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> i++
            indent < minIndent -> break
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                i++
                val code = StringBuilder()
                while (i < end && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                if (i < end) i++
                blocks += MdBlock.Code(lang, code.toString())
            }
            RulePattern.matches(trimmed) -> {
                blocks += MdBlock.Rule
                i++
            }
            HeadingPattern.matches(trimmed) -> {
                val match = HeadingPattern.matchEntire(trimmed)!!
                blocks += MdBlock.Heading(match.groupValues[1].length, parseInlines(match.groupValues[2].trim()))
                i++
            }
            trimmed.startsWith(">") -> {
                val quoted = mutableListOf<String>()
                while (i < end && lines[i].trimStart().startsWith(">")) {
                    var body = lines[i].trimStart().removePrefix(">")
                    if (body.startsWith(" ")) body = body.drop(1)
                    quoted += body
                    i++
                }
                blocks += MdBlock.Quote(parseLines(quoted, 0, quoted.size, 0).first)
            }
            isTableRow(trimmed) && i + 1 < end && TableSep.matches(lines[i + 1].trim()) -> {
                val header = splitCells(trimmed).map(::parseInlines)
                i += 2
                val rows = mutableListOf<List<List<MdInline>>>()
                while (i < end && isTableRow(lines[i].trim())) {
                    rows += splitCells(lines[i].trim()).map(::parseInlines)
                    i++
                }
                blocks += MdBlock.Table(header, rows)
            }
            ListPattern.matches(raw) -> {
                val (list, next) = parseList(lines, i, end)
                blocks += list
                i = next
            }
            else -> {
                val para = StringBuilder(trimmed)
                i++
                while (i < end) {
                    val next = lines[i]
                    val nextTrim = next.trim()
                    if (nextTrim.isEmpty() ||
                        HeadingPattern.matches(nextTrim) ||
                        nextTrim.startsWith("```") ||
                        nextTrim.startsWith(">") ||
                        ListPattern.matches(next) ||
                        RulePattern.matches(nextTrim) ||
                        (isTableRow(nextTrim) && i + 1 < end && TableSep.matches(lines[i + 1].trim()))
                    ) break
                    para.append('\n').append(nextTrim)
                    i++
                }
                val text = para.toString()
                val image = Regex("^!\\[(.*?)]\\((.*?)\\)$").matchEntire(text)
                blocks += if (image != null) {
                    MdBlock.Image(image.groupValues[1], image.groupValues[2].trim())
                } else {
                    MdBlock.Paragraph(parseInlines(text.replace('\n', ' ')))
                }
            }
        }
    }
    return blocks to i
}

private fun parseList(lines: List<String>, start: Int, end: Int): Pair<MdBlock.ListBlock, Int> {
    val first = ListPattern.matchEntire(lines[start]) ?: return MdBlock.ListBlock(false, emptyList()) to start
    val baseIndent = first.groupValues[1].length
    val ordered = first.groupValues[2].first().isDigit()
    val items = mutableListOf<MdListItem>()
    var i = start
    while (i < end) {
        val match = ListPattern.matchEntire(lines[i]) ?: break
        val indent = match.groupValues[1].length
        if (indent < baseIndent) break
        if (indent > baseIndent) break
        val markerOrdered = match.groupValues[2].first().isDigit()
        if (markerOrdered != ordered) break
        val checked = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { it.equals("x", true) }
        val firstLine = match.groupValues[4]
        i++
        val nested = mutableListOf<String>()
        nested += firstLine
        while (i < end) {
            val line = lines[i]
            if (line.trim().isEmpty()) {
                nested += ""
                i++
                continue
            }
            val nextIndent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
            val nextMatch = ListPattern.matchEntire(line)
            if (nextMatch != null && nextMatch.groupValues[1].length <= baseIndent) break
            if (nextIndent <= baseIndent && nextMatch == null && !line.startsWith(" ".repeat(baseIndent + 2))) break
            nested += line.drop((baseIndent + 2).coerceAtMost(line.length))
            i++
        }
        while (nested.isNotEmpty() && nested.last().isBlank()) nested.removeAt(nested.lastIndex)
        items += MdListItem(checked, parseLines(nested, 0, nested.size, 0).first)
    }
    return MdBlock.ListBlock(ordered, items) to i
}

private fun isTableRow(line: String): Boolean =
    line.contains('|') && !TableSep.matches(line)

private fun splitCells(line: String): List<String> {
    var value = line.trim()
    if (value.startsWith("|")) value = value.drop(1)
    if (value.endsWith("|")) value = value.dropLast(1)
    return value.split('|').map { it.trim() }
}

private fun parseInlines(source: String): List<MdInline> {
    val out = mutableListOf<MdInline>()
    val buf = StringBuilder()
    var i = 0
    fun flush() {
        if (buf.isNotEmpty()) {
            out += MdInline.Text(buf.toString())
            buf.clear()
        }
    }
    while (i < source.length) {
        if (source[i] == '\\' && i + 1 < source.length) {
            buf.append(source[i + 1])
            i += 2
            continue
        }
        if (source.startsWith("`", i)) {
            val close = source.indexOf('`', i + 1)
            if (close > i) {
                flush()
                out += MdInline.Code(source.substring(i + 1, close))
                i = close + 1
                continue
            }
        }
        val wrapped = takeWrapped(source, i, "**") ?: takeWrapped(source, i, "__")
        if (wrapped != null) {
            flush()
            out += MdInline.Bold(parseInlines(wrapped.first))
            i = wrapped.second
            continue
        }
        val strike = takeWrapped(source, i, "~~")
        if (strike != null) {
            flush()
            out += MdInline.Strike(parseInlines(strike.first))
            i = strike.second
            continue
        }
        val mark = takeWrapped(source, i, "==")
        if (mark != null) {
            flush()
            out += MdInline.Mark(parseInlines(mark.first))
            i = mark.second
            continue
        }
        if (source.startsWith("![", i)) {
            val parsed = takeLink(source, i, image = true)
            if (parsed != null) {
                flush()
                out += MdInline.Text(parsed.first)
                i = parsed.second
                continue
            }
        }
        if (source[i] == '[') {
            val parsed = takeLink(source, i, image = false)
            if (parsed != null) {
                flush()
                out += MdInline.Link(parseInlines(parsed.first), parsed.third)
                i = parsed.second
                continue
            }
        }
        val italicStar = takeWrapped(source, i, "*")
        if (italicStar != null) {
            flush()
            out += MdInline.Italic(parseInlines(italicStar.first))
            i = italicStar.second
            continue
        }
        if (source[i] == '_' && !source.getOrNull(i - 1).isWordChar() && source.getOrNull(i + 1) != '_') {
            val italicUnder = takeWrapped(source, i, "_")
            if (italicUnder != null && !source.getOrNull(italicUnder.second).isWordChar()) {
                flush()
                out += MdInline.Italic(parseInlines(italicUnder.first))
                i = italicUnder.second
                continue
            }
        }
        if (source[i] == '<') {
            val html = takeHtml(source, i)
            if (html != null) {
                flush()
                out += html.first
                i = html.second
                continue
            }
        }
        buf.append(source[i])
        i++
    }
    flush()
    return out
}

private fun takeWrapped(source: String, start: Int, delim: String): Pair<String, Int>? {
    if (!source.startsWith(delim, start)) return null
    if (delim == "*" && source.startsWith("**", start)) return null
    var i = start + delim.length
    while (i <= source.length - delim.length) {
        if (source[i] == '\\') {
            i += 2
            continue
        }
        if (source.startsWith(delim, i) && i > start + delim.length) {
            return source.substring(start + delim.length, i) to i + delim.length
        }
        if (source[i] == '\n') return null
        i++
    }
    return null
}

private fun takeLink(source: String, start: Int, image: Boolean): Triple<String, Int, String>? {
    val open = if (image) start + 2 else start + 1
    if (image && !source.startsWith("![", start)) return null
    if (!image && source[start] != '[') return null
    val close = source.indexOf(']', open)
    if (close < 0 || close + 1 >= source.length || source[close + 1] != '(') return null
    val urlEnd = source.indexOf(')', close + 2)
    if (urlEnd < 0) return null
    val label = source.substring(open, close)
    val url = source.substring(close + 2, urlEnd).trim()
    return Triple(label, urlEnd + 1, url)
}

private fun takeHtml(source: String, start: Int): Pair<List<MdInline>, Int>? {
    val rest = source.substring(start)
    Regex("(?is)^<br\\s*/?>").find(rest)?.let {
        return listOf(MdInline.Text("\n")) to start + it.value.length
    }
    val pair = Regex("(?is)^<(mark|code|strong|b|em|i|del|s)>(.*?)</\\1>").find(rest) ?: return null
    val tag = pair.groupValues[1].lowercase()
    val inner = parseInlines(pair.groupValues[2])
    val node = when (tag) {
        "mark" -> MdInline.Mark(inner)
        "code" -> MdInline.Code(pair.groupValues[2])
        "strong", "b" -> MdInline.Bold(inner)
        "em", "i" -> MdInline.Italic(inner)
        else -> MdInline.Strike(inner)
    }
    return listOf(node) to start + pair.value.length
}

private fun Char?.isWordChar(): Boolean = this != null && (isLetterOrDigit() || this == '_')

private fun highlightCode(code: String, language: String, palette: MdPalette) = buildAnnotatedString {
    val keywords = keywordsFor(language)
    var i = 0
    while (i < code.length) {
        when {
            code.startsWith("//", i) || (language.startsWith("py") && code[i] == '#' && (i == 0 || code[i - 1] == '\n')) -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                withStyle(SpanStyle(color = palette.comment)) { append(code.substring(i, end)) }
                i = end
            }
            code.startsWith("/*", i) -> {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                withStyle(SpanStyle(color = palette.comment)) { append(code.substring(i, end)) }
                i = end
            }
            code[i] == '"' || code[i] == '\'' -> {
                val quote = code[i]
                var j = i + 1
                while (j < code.length && code[j] != quote) {
                    if (code[j] == '\\') j++
                    j++
                }
                if (j < code.length) j++
                withStyle(SpanStyle(color = palette.string)) { append(code.substring(i, j.coerceAtMost(code.length))) }
                i = j
            }
            code[i].isDigit() -> {
                var j = i
                while (j < code.length && (code[j].isDigit() || code[j] == '.')) j++
                withStyle(SpanStyle(color = palette.keyword)) { append(code.substring(i, j)) }
                i = j
            }
            code[i].isLetter() || code[i] == '_' -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (word in keywords) withStyle(SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium)) {
                    append(word)
                } else append(word)
                i = j
            }
            else -> {
                append(code[i])
                i++
            }
        }
    }
}

private fun keywordsFor(language: String): Set<String> {
    val generic = setOf(
        "if", "else", "for", "while", "return", "class", "fun", "function", "def",
        "const", "var", "val", "let", "true", "false", "null", "nil", "import",
        "package", "public", "private", "protected", "void", "int", "new", "this",
        "break", "continue", "switch", "case", "try", "catch", "finally", "throw",
        "async", "await", "from", "in", "of", "as", "is"
    )
    return when (language.lowercase()) {
        "kt", "kotlin" -> generic + setOf("data", "object", "companion", "suspend", "override", "inner")
        "java" -> generic + setOf("static", "final", "extends", "implements", "interface")
        "js", "javascript", "ts", "typescript" -> generic + setOf("export", "default", "typeof", "undefined")
        "py", "python" -> generic + setOf("elif", "None", "True", "False", "with", "lambda", "yield", "self")
        "lua" -> setOf("local", "function", "end", "then", "elseif", "repeat", "until", "and", "or", "not", "nil", "true", "false")
        "json" -> setOf("true", "false", "null")
        else -> generic
    }
}

private fun loadRemoteBitmap(url: String): ImageBitmap? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PVZRH-Launcher/1.0")
        }
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

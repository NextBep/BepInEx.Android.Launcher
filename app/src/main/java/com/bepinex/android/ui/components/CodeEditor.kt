package com.bepinex.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bepinex.android.R

private data class CodeEditorPalette(
    val background: Color,
    val gutter: Color,
    val foreground: Color,
    val lineNumber: Color,
    val currentLineNumber: Color,
    val border: Color,
    val statusBar: Color,
    val cursor: Color,
    val highlight: SyntaxHighlightColors
)

@Composable
private fun rememberCodeEditorPalette(): CodeEditorPalette {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(isDark) {
        if (isDark) {
            CodeEditorPalette(
                background = Color(0xFF1E1E1E),
                gutter = Color(0xFF181818),
                foreground = Color(0xFFD4D4D4),
                lineNumber = Color(0xFF858585),
                currentLineNumber = Color(0xFFC6C6C6),
                border = Color(0xFF2B2B2B),
                statusBar = Color(0xFF191919),
                cursor = Color(0xFFAEAFAD),
                highlight = editorSyntaxColors(true)
            )
        } else {
            CodeEditorPalette(
                background = Color(0xFFFCFCFC),
                gutter = Color(0xFFF3F3F3),
                foreground = Color(0xFF1E1E1E),
                lineNumber = Color(0xFF6E7681),
                currentLineNumber = Color(0xFF1F2328),
                border = Color(0xFFE4E4E4),
                statusBar = Color(0xFFF6F6F6),
                cursor = Color(0xFF1E1E1E),
                highlight = editorSyntaxColors(false)
            )
        }
    }
}

private val EditorFontSize = 13.sp
private val EditorLineHeight = 20.sp

private val EditorTextStyle = TextStyle(
    fontSize = EditorFontSize,
    lineHeight = EditorLineHeight,
    fontFamily = FontFamily.Monospace,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

/**
 * Code editor with line numbers, JSON/Lua highlighting, and wrap/no-wrap layout.
 */
@Composable
internal fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    extension: String,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    showStatusBar: Boolean = true,
    placeholder: String = ""
) {
    val palette = rememberCodeEditorPalette()
    val language = remember(extension) { syntaxLanguageFor(extension) }
    val languageLabel = remember(language, extension) { syntaxLanguageLabel(language, extension) }
    val highlighting = remember(extension, palette.highlight) {
        SyntaxHighlightVisualTransformation(extension, palette.highlight)
    }
    val textStyle = remember(palette.foreground) {
        EditorTextStyle.copy(color = palette.foreground)
    }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val sourceLineCount = remember(value.text) { 1 + value.text.count { it == '\n' } }
    val currentLine = remember(value.text, value.selection.start) {
        val caret = value.selection.start.coerceIn(0, value.text.length)
        1 + value.text.take(caret).count { it == '\n' }
    }
    val currentColumn = remember(value.text, value.selection.start) {
        val caret = value.selection.start.coerceIn(0, value.text.length)
        if (caret == 0) 1 else caret - value.text.lastIndexOf('\n', caret - 1)
    }
    val gutterWidth = rememberGutterWidth(sourceLineCount, textStyle)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = palette.background,
        border = BorderStroke(1.dp, palette.border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (showLineNumbers) {
                        Box(
                            Modifier
                                .width(gutterWidth)
                                .fillMaxHeight()
                                .background(palette.gutter)
                        )
                        VerticalDivider(
                            thickness = 1.dp,
                            color = palette.border
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(palette.background)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(vertical = 10.dp)
                ) {
                    if (showLineNumbers) {
                        LineNumberGutter(
                            text = value.text,
                            layout = textLayout,
                            sourceLineCount = sourceLineCount,
                            currentLine = currentLine,
                            wordWrap = wordWrap,
                            textStyle = textStyle,
                            numberColor = palette.lineNumber,
                            currentNumberColor = palette.currentLineNumber,
                            modifier = Modifier.width(gutterWidth)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                            .padding(start = 8.dp, end = 12.dp)
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = { if (!readOnly) onValueChange(it) },
                            modifier = if (wordWrap) Modifier.fillMaxWidth() else Modifier,
                            enabled = true,
                            readOnly = readOnly,
                            textStyle = textStyle,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default
                            ),
                            visualTransformation = highlighting,
                            onTextLayout = { textLayout = it },
                            cursorBrush = SolidColor(palette.cursor),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                                        Text(
                                            text = placeholder,
                                            style = textStyle.copy(color = palette.lineNumber)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }

            if (showStatusBar) {
                HorizontalDivider(thickness = 1.dp, color = palette.border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.statusBar)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.viewer_cursor_position, currentLine, currentColumn),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.lineNumber,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = languageLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = when (language) {
                            SyntaxLanguage.JSON -> palette.highlight.property
                            SyntaxLanguage.LUA -> palette.highlight.keyword
                            SyntaxLanguage.PLAIN_TEXT -> palette.lineNumber
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberGutterWidth(sourceLineCount: Int, textStyle: TextStyle): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val digits = sourceLineCount.toString().length.coerceAtLeast(2)
    return remember(digits, textStyle, textMeasurer, density) {
        val measured = textMeasurer.measure(
            text = "8".repeat(digits),
            style = textStyle,
            maxLines = 1,
            softWrap = false
        )
        with(density) { measured.size.width.toDp() } + 16.dp
    }
}

@Composable
private fun LineNumberGutter(
    text: String,
    layout: TextLayoutResult?,
    sourceLineCount: Int,
    currentLine: Int,
    wordWrap: Boolean,
    textStyle: TextStyle,
    numberColor: Color,
    currentNumberColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val gutterStyle = textStyle.copy(
        color = numberColor,
        textAlign = TextAlign.End
    )

    if (!wordWrap || layout == null) {
        val digits = sourceLineCount.toString().length.coerceAtLeast(2)
        val numbers = remember(sourceLineCount, currentLine, digits, numberColor, currentNumberColor) {
            buildAnnotatedString {
                for (line in 1..sourceLineCount) {
                    if (line > 1) append('\n')
                    val start = length
                    append(line.toString().padStart(digits))
                    addStyle(
                        SpanStyle(
                            color = if (line == currentLine) currentNumberColor else numberColor,
                            fontWeight = if (line == currentLine) FontWeight.Medium else FontWeight.Normal
                        ),
                        start,
                        length
                    )
                }
            }
        }
        Text(
            text = numbers,
            style = gutterStyle,
            modifier = modifier.padding(start = 8.dp, end = 8.dp),
            softWrap = false
        )
        return
    }

    val marks = remember(text, layout) { sourceLineMarks(text, layout) }
    val contentHeight = with(density) { layout.size.height.toDp() }
    val textMeasurer = rememberTextMeasurer()
    val rightPaddingPx = with(density) { 8.dp.toPx() }

    Canvas(modifier = modifier.height(contentHeight)) {
        for (index in marks.indices) {
            val line = index + 1
            val mark = marks[index]
            val measured = textMeasurer.measure(
                text = line.toString(),
                style = gutterStyle.copy(
                    color = if (line == currentLine) currentNumberColor else numberColor,
                    fontWeight = if (line == currentLine) FontWeight.Medium else FontWeight.Normal
                ),
                maxLines = 1,
                softWrap = false
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = size.width - measured.size.width - rightPaddingPx,
                    y = mark.top + ((mark.height - measured.size.height) / 2f)
                )
            )
        }
    }
}

private data class LineGutterMark(val top: Float, val height: Float)

private fun sourceLineMarks(text: String, layout: TextLayoutResult): List<LineGutterMark> {
    if (layout.lineCount <= 0) return listOf(LineGutterMark(0f, 0f))
    val marks = ArrayList<LineGutterMark>(text.count { it == '\n' } + 1)
    var offset = 0
    val lastIndex = layout.layoutInput.text.length
    while (true) {
        val visual = layout.getLineForOffset(offset.coerceIn(0, lastIndex))
        val top = layout.getLineTop(visual)
        marks.add(LineGutterMark(top, layout.getLineBottom(visual) - top))
        val newline = text.indexOf('\n', startIndex = offset)
        if (newline < 0) break
        offset = newline + 1
        if (offset > text.length) break
    }
    return marks
}

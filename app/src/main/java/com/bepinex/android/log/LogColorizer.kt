package com.bepinex.android.log

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor

object LogColorizer {

    val panelBackground = Color.parseColor("#E6111219")
    val headerBackground = Color.parseColor("#F2181825")
    val panelStroke = Color.parseColor("#3D89B4FA")
    val accent = Color.parseColor("#89B4FA")
    val text = Color.parseColor("#CDD6F4")
    val muted = Color.parseColor("#7F849C")
    val fatal = Color.parseColor("#E64553")
    val error = Color.parseColor("#F38BA8")
    val warning = Color.parseColor("#FAB387")
    val info = Color.parseColor("#89B4FA")
    val message = Color.parseColor("#A6E3A1")
    val debug = Color.parseColor("#6C7086")

    private val levelRegex = Regex(
        """\[(Fatal|Error|Warning|Warn|Info|Message|Msg|Debug)\b""",
        RegexOption.IGNORE_CASE
    )

    fun taggedColor(line: String): Int? {
        val trimmed = line.trimStart()
        val tagged = levelRegex.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase()
        if (tagged != null) {
            return when (tagged) {
                "fatal" -> fatal
                "error" -> error
                "warning", "warn" -> warning
                "info" -> info
                "message", "msg" -> message
                "debug" -> debug
                else -> text
            }
        }
        if (trimmed.length >= 2 && trimmed[1] == '/') {
            return when (trimmed[0]) {
                'F' -> fatal
                'E' -> error
                'W' -> warning
                'I' -> info
                'D', 'V' -> debug
                else -> null
            }
        }
        return null
    }

    fun colorsFor(lines: List<String>): List<Int> {
        var current = text
        return lines.map { line ->
            taggedColor(line)?.also { current = it } ?: current
        }
    }

    fun spannable(lines: Iterable<String>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        var current = text
        for (line in lines) {
            taggedColor(line)?.let { current = it }
            val start = builder.length
            builder.append(line)
            builder.setSpan(
                ForegroundColorSpan(current),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append('\n')
        }
        return builder
    }
}

@Composable
fun logLineComposeColors(lines: List<String>): List<ComposeColor> {
    val argb = remember(lines) { LogColorizer.colorsFor(lines) }
    return argb.map { ComposeColor(it.toLong() and 0xFFFFFFFFL) }
}

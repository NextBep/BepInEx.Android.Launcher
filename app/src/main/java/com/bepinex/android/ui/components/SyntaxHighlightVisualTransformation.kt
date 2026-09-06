package com.bepinex.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal data class SyntaxHighlightColors(
    val property: Color,
    val string: Color,
    val number: Color,
    val boolean: Color,
    val nullLiteral: Color,
    val keyword: Color,
    val function: Color,
    val builtin: Color,
    val comment: Color
)

internal class SyntaxHighlightVisualTransformation(
    extension: String,
    private val colors: SyntaxHighlightColors
) : VisualTransformation {
    private val language = when (extension.lowercase()) {
        "json", "json5" -> Language.JSON
        "lua" -> Language.LUA
        else -> Language.PLAIN_TEXT
    }

    override fun filter(text: AnnotatedString): TransformedText {
        if (language == Language.PLAIN_TEXT || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val highlighted = AnnotatedString.Builder(text.text).apply {
            when (language) {
                Language.JSON -> highlightJson(text.text)
                Language.LUA -> highlightLua(text.text)
                Language.PLAIN_TEXT -> Unit
            }
        }.toAnnotatedString()
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    private fun AnnotatedString.Builder.highlightJson(source: String) {
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = lineEnd(source, index + 2)
                    addCommentStyle(index, end)
                    index = end
                }

                source.startsWith("/*", index) -> {
                    val end = blockEnd(source, index + 2, "*/")
                    addCommentStyle(index, end)
                    index = end
                }

                source[index] == '"' || source[index] == '\'' -> {
                    val end = quotedStringEnd(source, index, source[index])
                    val isProperty = nextNonWhitespaceIsColon(source, end)
                    addStyle(
                        SpanStyle(
                            color = if (isProperty) colors.property else colors.string,
                            fontWeight = if (isProperty) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        index,
                        end
                    )
                    index = end
                }

                isJsonNumberStart(source, index) -> {
                    val end = jsonNumberEnd(source, index)
                    if (end > index) {
                        addStyle(SpanStyle(color = colors.number), index, end)
                        index = end
                    } else {
                        index++
                    }
                }

                isIdentifierStart(source[index]) -> {
                    val end = identifierEnd(source, index)
                    val token = source.substring(index, end)
                    when {
                        token == "true" || token == "false" -> addStyle(
                            SpanStyle(color = colors.boolean, fontWeight = FontWeight.SemiBold),
                            index,
                            end
                        )

                        token == "null" -> addStyle(
                            SpanStyle(color = colors.nullLiteral, fontStyle = FontStyle.Italic),
                            index,
                            end
                        )

                        token == "Infinity" || token == "NaN" -> addStyle(
                            SpanStyle(color = colors.number),
                            index,
                            end
                        )

                        nextNonWhitespaceIsColon(source, end) -> addStyle(
                            SpanStyle(color = colors.property, fontWeight = FontWeight.SemiBold),
                            index,
                            end
                        )
                    }
                    index = end
                }

                else -> index++
            }
        }
    }

    private fun AnnotatedString.Builder.highlightLua(source: String) {
        var index = 0
        var expectFunctionName = false
        while (index < source.length) {
            when {
                source.startsWith("--[[", index) -> {
                    val end = blockEnd(source, index + 4, "]]")
                    addCommentStyle(index, end)
                    index = end
                }

                source.startsWith("--", index) -> {
                    val end = lineEnd(source, index + 2)
                    addCommentStyle(index, end)
                    index = end
                }

                source.startsWith("[[", index) -> {
                    val end = blockEnd(source, index + 2, "]]")
                    addStyle(SpanStyle(color = colors.string), index, end)
                    index = end
                }

                source[index] == '"' || source[index] == '\'' -> {
                    val end = quotedStringEnd(source, index, source[index])
                    addStyle(SpanStyle(color = colors.string), index, end)
                    index = end
                }

                source[index].isDigit() ||
                    (source[index] == '.' && source.getOrNull(index + 1)?.isDigit() == true) -> {
                    val end = luaNumberEnd(source, index)
                    addStyle(SpanStyle(color = colors.number), index, end)
                    index = end
                }

                isIdentifierStart(source[index]) -> {
                    val end = luaIdentifierEnd(source, index)
                    val token = source.substring(index, end)
                    when {
                        token == "true" || token == "false" -> addStyle(
                            SpanStyle(color = colors.boolean, fontWeight = FontWeight.SemiBold),
                            index,
                            end
                        )

                        token == "nil" -> addStyle(
                            SpanStyle(color = colors.nullLiteral, fontStyle = FontStyle.Italic),
                            index,
                            end
                        )

                        token in LUA_KEYWORDS -> addStyle(
                            SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold),
                            index,
                            end
                        )

                        token in LUA_BUILTINS -> addStyle(
                            SpanStyle(color = colors.builtin, fontWeight = FontWeight.Medium),
                            index,
                            end
                        )

                        expectFunctionName || nextNonWhitespaceIsOpeningParen(source, end) -> addStyle(
                            SpanStyle(color = colors.function, fontWeight = FontWeight.Medium),
                            index,
                            end
                        )
                    }
                    expectFunctionName = token == "function"
                    index = end
                }

                !source[index].isWhitespace() && expectFunctionName -> {
                    expectFunctionName = false
                    index++
                }

                else -> index++
            }
        }
    }

    private fun AnnotatedString.Builder.addCommentStyle(start: Int, end: Int) {
        addStyle(
            SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic),
            start,
            end
        )
    }

    private enum class Language {
        JSON,
        LUA,
        PLAIN_TEXT
    }

    private companion object {
        val LUA_KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end", "for", "function",
            "goto", "if", "in", "local", "not", "or", "repeat", "return", "then",
            "until", "while"
        )

        val LUA_BUILTINS = setOf(
            "_G", "_VERSION", "assert", "collectgarbage", "dofile", "error", "getmetatable",
            "ipairs", "load", "loadfile", "next", "pairs", "pcall", "print", "rawequal",
            "rawget", "rawlen", "rawset", "require", "select", "setmetatable", "tonumber",
            "tostring", "type", "warn", "xpcall", "coroutine", "debug", "io", "math",
            "os", "package", "string", "table", "utf8"
        )

        fun quotedStringEnd(source: String, start: Int, quote: Char): Int {
            var index = start + 1
            var escaped = false
            while (index < source.length) {
                val char = source[index]
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    return index + 1
                }
                index++
            }
            return source.length
        }

        fun lineEnd(source: String, start: Int): Int {
            val newline = source.indexOf('\n', start)
            return if (newline >= 0) newline else source.length
        }

        fun blockEnd(source: String, contentStart: Int, closing: String): Int {
            val closingStart = source.indexOf(closing, contentStart)
            return if (closingStart >= 0) closingStart + closing.length else source.length
        }

        fun nextNonWhitespaceIsColon(source: String, start: Int): Boolean {
            var index = start
            while (index < source.length && source[index].isWhitespace()) index++
            return source.getOrNull(index) == ':'
        }

        fun nextNonWhitespaceIsOpeningParen(source: String, start: Int): Boolean {
            var index = start
            while (index < source.length && source[index].isWhitespace()) index++
            return source.getOrNull(index) == '('
        }

        fun isIdentifierStart(char: Char): Boolean = char.isLetter() || char == '_' || char == '$'

        fun identifierEnd(source: String, start: Int): Int {
            var index = start + 1
            while (index < source.length &&
                (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')
            ) {
                index++
            }
            return index
        }

        fun luaIdentifierEnd(source: String, start: Int): Int {
            var index = identifierEnd(source, start)
            while (index < source.length && (source[index] == '.' || source[index] == ':')) {
                val memberStart = index + 1
                if (memberStart >= source.length || !isIdentifierStart(source[memberStart])) break
                index = identifierEnd(source, memberStart)
            }
            return index
        }

        fun isJsonNumberStart(source: String, index: Int): Boolean {
            val char = source[index]
            return char.isDigit() ||
                ((char == '-' || char == '+') && source.getOrNull(index + 1)?.let {
                    it.isDigit() || it == '.'
                } == true) ||
                (char == '.' && source.getOrNull(index + 1)?.isDigit() == true)
        }

        fun jsonNumberEnd(source: String, start: Int): Int {
            var index = start
            if (source[index] == '-' || source[index] == '+') index++
            if (source.startsWith("0x", index, ignoreCase = true)) {
                index += 2
                while (index < source.length && source[index].isHexDigit()) index++
                return index
            }
            while (index < source.length && source[index].isDigit()) index++
            if (index < source.length && source[index] == '.') {
                index++
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            return index
        }

        fun luaNumberEnd(source: String, start: Int): Int {
            var index = start
            if (source.startsWith("0x", index, ignoreCase = true)) {
                index += 2
                while (index < source.length && (source[index].isHexDigit() || source[index] == '.')) index++
                if (index < source.length && (source[index] == 'p' || source[index] == 'P')) {
                    index++
                    if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                    while (index < source.length && source[index].isDigit()) index++
                }
                return index
            }
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            return index
        }

        fun Char.isHexDigit(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
    }
}

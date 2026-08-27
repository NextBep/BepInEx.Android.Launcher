package com.bepinex.android.log

import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads and tails BepInEx runtime LogOutput.log.
 *
 * BepInEx writes its logs to `BepInEx/LogOutput.log` via the DiskLogListener.
 * This reader polls the file periodically and exposes new lines as a StateFlow.
 */
object BepInExLogReader {

    data class LogLine(
        val timestamp: String,
        val level: LogLevel,
        val source: String,
        val message: String
    )

    enum class LogLevel(val label: String) {
        FATAL("Fatal"),
        ERROR("Error"),
        WARNING("Warning"),
        MESSAGE("Message"),
        INFO("Info"),
        DEBUG("Debug"),
        UNKNOWN("");

        companion object {
            fun parse(levelStr: String): LogLevel = when (levelStr.lowercase()) {
                "fatal" -> FATAL
                "error" -> ERROR
                "warning", "warn" -> WARNING
                "message", "msg" -> MESSAGE
                "info" -> INFO
                "debug" -> DEBUG
                else -> UNKNOWN
            }
        }
    }

    /** BepInEx log format: [LogLevel :Source] message */
    private val LOG_PATTERN = Regex("^\\[([A-Za-z]+)\\s*:?(\\S+)?\\]\\s?(.*)")

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    private var pollingJob: Job? = null
    private var lastFileSize = 0L
    private var currentLogFile: File? = null

    fun startWatching(packageName: String, scope: CoroutineScope, modpackName: String? = null) {
        startWatchingFile(BepInExPaths.getModpackLogFile(packageName, modpackName), scope)
    }

    fun startWatchingFile(logFile: File, scope: CoroutineScope) {
        if (currentLogFile?.absolutePath == logFile.absolutePath) return
        stopWatching()
        currentLogFile = logFile
        lastFileSize = 0L
        _lines.value = emptyList()

        readNewLines()

        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(500)
                readNewLines()
            }
        }
    }

    fun stopWatching() {
        pollingJob?.cancel()
        pollingJob = null
        currentLogFile = null
        lastFileSize = 0L
    }

    fun clear() {
        _lines.value = emptyList()
        lastFileSize = 0L
    }

    private fun readNewLines() {
        try {
            val logFile = currentLogFile ?: return
            if (!logFile.exists()) return

            val currentSize = logFile.length()
            if (currentSize <= lastFileSize) return

            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(lastFileSize)
                val newBytes = ByteArray((currentSize - lastFileSize).toInt())
                raf.readFully(newBytes)
                val newContent = String(newBytes, Charsets.UTF_8)
                val newLines = newContent.lines().filter { it.isNotBlank() }.mapNotNull { parseLine(it) }
                if (newLines.isNotEmpty()) {
                    _lines.value = _lines.value + newLines
                }
                lastFileSize = currentSize
            }
        } catch (_: Exception) {
            // File may be locked or not yet written
        }
    }

    private fun parseLine(raw: String): LogLine? {
        val match = LOG_PATTERN.find(raw) ?: return LogLine(
            timestamp = "",
            level = LogLevel.UNKNOWN,
            source = "",
            message = raw
        )
        val level = LogLevel.parse(match.groupValues[1])
        val source = match.groupValues[2].ifEmpty { "" }
        val message = match.groupValues[3]
        return LogLine(timestamp = "", level = level, source = source, message = message)
    }
}

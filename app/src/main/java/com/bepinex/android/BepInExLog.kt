package com.bepinex.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object BepInExLog {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val message: String
    )

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun init(context: Context) {
        // Write log to external storage so we can adb pull it
        logFile = File(context.getExternalFilesDir(null), "bepinex_launcher.log")
        // Also capture native logcat for crash diagnosis
        startLogcatCapture(context)
    }

    // Logcat capture

    private var logcatThread: Thread? = null

    private fun startLogcatCapture(context: Context) {
        val logcatFile = File(context.getExternalFilesDir(null), "logcat.txt")
        logcatThread = Thread({
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "threadtime", "-t", "0")
                )
                logcatFile.outputStream().use { out ->
                    process.inputStream.copyTo(out)
                }
            } catch (_: Exception) { }
        }, "logcat-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** Set per-game log file path (for BepInEx LogOutput.log viewer) */
    fun getBepInExLogFile(packageName: String): File =
        BepInExPaths.getLogFile(packageName)

    fun getEntries(): List<LogEntry> = _entries.value

    fun i(message: String) = log(Level.INFO, message)
    fun w(message: String) = log(Level.WARN, message)
    fun e(message: String, throwable: Throwable? = null) {
        log(Level.ERROR, message)
        throwable?.let { log(Level.ERROR, it.stackTraceToString()) }
    }
    fun d(message: String) = log(Level.DEBUG, message)

    /** Clear in-memory log entries */
    fun clear() { _entries.value = emptyList() }

    private fun log(level: Level, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, level, message)
        _entries.value = _entries.value + entry

        // Write to file
        logFile?.appendText("[$timestamp] [${level.name}] $message\n")

        // Output to logcat
        val prio = when (level) {
            Level.ERROR -> android.util.Log.ERROR
            Level.WARN -> android.util.Log.WARN
            Level.INFO -> android.util.Log.INFO
            Level.DEBUG -> android.util.Log.DEBUG
        }
        android.util.Log.println(prio, "BepInEx", message)
    }
}

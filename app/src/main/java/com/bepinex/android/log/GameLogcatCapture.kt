package com.bepinex.android.log

import com.bepinex.android.BepInExLog
import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Captures logcat output from the :game process to a file.
 *
 * This is started in BootstrapActivity's :game process so that
 * logcat is captured in the same process where the crash occurs.
 * MainActivity then reads this file in the crash recovery dialog.
 *
 * Modeled after FusionCore's LogcatCapture.java.
 */
object GameLogcatCapture {

    private const val TAG = "GameLogcatCapture"
    private const val BUFFER_SIZE = 8192
    private const val MAX_LINES = 10_000

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Starts capturing logcat for the given package.
     * Writes to {BepInExConfigPath}/logs/game_crash.log
     */
    fun start(packageName: String) {
        if (captureJob?.isActive == true) {
            BepInExLog.i("$TAG: Already capturing")
            return
        }

        val logDir = File(BepInExPaths.getBepInExDir(packageName), "logs")
        logDir.mkdirs()
        val logFile = File(logDir, "game_crash.log")

        // Clear previous log
        logFile.writeText("")

        captureJob = scope.launch {
            try {
                captureLogcat(logFile)
            } catch (e: CancellationException) {
                // Normal shutdown
            } catch (e: Exception) {
                BepInExLog.w("$TAG: Capture error: ${e.message}")
            }
        }

        BepInExLog.i("$TAG: Started capturing logcat to ${logFile.absolutePath}")
    }

    /**
     * Stops the current capture session.
     */
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        BepInExLog.i("$TAG: Stopped capturing logcat")
    }

    /**
     * Returns the log file for the given package, or null if it doesn't exist.
     */
    fun getLogFile(packageName: String): File? {
        val logFile = File(BepInExPaths.getBepInExDir(packageName), "logs/game_crash.log")
        return if (logFile.exists() && logFile.length() > 0) logFile else null
    }

    private suspend fun captureLogcat(logFile: File) = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(arrayOf(
            "logcat",
            "-v", "time",
            "--pid", android.os.Process.myPid().toString()
        ))

        var lineCount = 0
        try {
            BufferedReader(InputStreamReader(process.inputStream), BUFFER_SIZE).use { reader ->
                logFile.bufferedWriter().use { writer ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (lineCount >= MAX_LINES) {
                            writer.write("... (log truncated at $MAX_LINES lines)\n")
                            break
                        }
                        writer.write(line)
                        writer.newLine()
                        lineCount++
                    }
                }
            }
        } finally {
            process.destroy()
        }
    }
}

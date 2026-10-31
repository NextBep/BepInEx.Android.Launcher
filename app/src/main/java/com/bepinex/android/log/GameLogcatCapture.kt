package com.bepinex.android.log

import com.bepinex.android.BepInExPaths
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams this process's logcat into a file under BepInEx/.
 *
 * MainActivity cannot reliably read the :game process logcat on modern Android,
 * so capture has to run inside :game and flush on every error line.
 *
 * Ported from the PVZRH launcher.
 */
object GameLogcatCapture {

    private const val MAX_CHUNK_BYTES = 4L * 1024L * 1024L

    private val processRef = AtomicReference<Process?>(null)
    private val threadRef = AtomicReference<Thread?>(null)

    fun start(packageName: String) {
        stop()
        val outFile = BepInExPaths.getLogcatCaptureFile(packageName)
        outFile.parentFile?.mkdirs()
        preservePreviousRun(outFile, BepInExPaths.getPreviousLogcatCaptureFile(packageName))
        prepareJavaCrashCapture(packageName)
        outFile.writeText("=== game logcat session ${Date()} pid=${android.os.Process.myPid()} ===\n")

        val thread = Thread({
            val proc = try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat",
                        "-v", "threadtime",
                        "-T", "1",
                        "Unity:V",
                        "BepInEx:V",
                        "AndroidRuntime:V",
                        "DEBUG:V",
                        "libc:V",
                        "crash_dump64:V",
                        "tombstoned:V",
                        "ActivityManager:I",
                        "*:S"
                    )
                )
            } catch (error: Exception) {
                outFile.appendText("logcat start failed: ${error.stackTraceToString()}\n")
                return@Thread
            }
            processRef.set(proc)
            try {
                var output = FileOutputStream(outFile, true)
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                        if (outFile.length() + bytes.size > MAX_CHUNK_BYTES) {
                            output.close()
                            rotateChunk(packageName, outFile)
                            output = FileOutputStream(outFile, true)
                            output.write("=== continued game logcat ${Date()} ===\n".toByteArray())
                        }
                        // The tag filter already limits the stream. Keep every emitted line so
                        // exception causes, Java frames and native backtraces remain intact.
                        output.write(bytes)
                        if (isRelevant(line)) {
                            output.flush()
                            try { output.fd.sync() } catch (_: Exception) { }
                        }
                    }
                } finally {
                    output.close()
                }
                val errors = proc.errorStream.bufferedReader().use { it.readText() }
                if (errors.isNotBlank()) outFile.appendText("\n[logcat stderr]\n$errors")
            } catch (error: Exception) {
                try { outFile.appendText("\nlogcat capture failed: ${error.stackTraceToString()}\n") } catch (_: Exception) { }
            } finally {
                proc.destroy()
                processRef.compareAndSet(proc, null)
            }
        }, "GameLogcatCapture")
        thread.isDaemon = true
        threadRef.set(thread)
        thread.start()
    }

    fun stop() {
        processRef.getAndSet(null)?.destroy()
        threadRef.getAndSet(null)?.interrupt()
    }

    fun isRelevant(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("fatal exception")) return true
        if (lower.contains("[error") || lower.contains("[fatal")) return true
        if (lower.contains("notsupportedexception")) return true
        if (lower.contains("il2cppinterop") &&
            (lower.contains("error") || lower.contains("exception"))
        ) {
            return true
        }
        if (lower.contains("signal") &&
            (lower.contains("sigsegv") || lower.contains("sigabrt") ||
                lower.contains("sigbus") || lower.contains("sigfpe"))
        ) {
            return true
        }
        return Regex("\\sE\\s+Unity\\b").containsMatchIn(line)
    }

    private fun rotateChunk(packageName: String, current: File) {
        val archive = BepInExPaths.getLogcatCaptureArchiveFile(packageName)
        archive.delete()
        if (!current.renameTo(archive)) {
            current.copyTo(archive, overwrite = true)
            current.delete()
        }
    }

    private fun preservePreviousRun(current: File, previous: File) {
        previous.delete()
        if (!current.isFile || current.length() == 0L) return
        if (!current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.delete()
        }
    }

    private fun prepareJavaCrashCapture(packageName: String) {
        val crashFile = BepInExPaths.getJavaCrashFile(packageName)
        preservePreviousRun(crashFile, BepInExPaths.getPreviousJavaCrashFile(packageName))
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile.parentFile?.mkdirs()
                FileOutputStream(crashFile, true).use { output ->
                    output.write(buildString {
                        appendLine("time=${Date()}")
                        appendLine("process=${android.os.Process.myPid()}")
                        appendLine("thread=${thread.name} (${thread.id})")
                        appendLine()
                        append(error.stackTraceToString())
                        appendLine()
                    }.toByteArray(Charsets.UTF_8))
                    output.flush()
                    try { output.fd.sync() } catch (_: Exception) { }
                }
            } catch (_: Throwable) { }
            delegate?.uncaughtException(thread, error)
        }
    }
}

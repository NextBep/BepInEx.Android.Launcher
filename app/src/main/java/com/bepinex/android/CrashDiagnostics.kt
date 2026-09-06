package com.bepinex.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Collects diagnostics without assuming anything about a target game. */
object CrashDiagnostics {
    data class PendingLaunch(val packageName: String, val startedAt: Long)

    private const val PREFS = "crash_diagnostics"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_STARTED_AT = "pending_started_at"
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private val logNames = setOf(
        "LogOutput.log", "main.log", "il2cpp.log", "output_log.txt", "player.log",
        "bepinexlogoutput.log", "BepInExLogOutput.log"
    )

    fun markLaunch(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, packageName)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun pending(context: Context): PendingLaunch? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        return PendingLaunch(packageName, prefs.getLong(KEY_STARTED_AT, 0L))
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    fun export(context: Context, packageName: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(context.cacheDir, "bepinex_diagnostics_${packageName}_$stamp.zip")
        output.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addText(zip, "device.txt", buildString {
                appendLine("launcherPackage=${context.packageName}")
                appendLine("targetPackage=$packageName")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("model=${Build.MODEL}")
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("time=${Date()}")
            })
            addCommand(zip, "logcat.txt", arrayOf("logcat", "-d", "-v", "threadtime"))
            addCommand(zip, "crash_logcat.txt", arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime"))
            addCommand(zip, "exit_info.txt", arrayOf("dumpsys", "activity", "exit-info", packageName))
            addCommand(zip, "package_info.txt", arrayOf("dumpsys", "package", packageName))
            addCommand(zip, "meminfo.txt", arrayOf("dumpsys", "meminfo", packageName))
            addCommand(zip, "tombstones.txt", arrayOf("ls", "-lt", "/data/tombstones"))

            addFileIfPresent(zip, "launcher/bepinex_launcher.log",
                File(context.getExternalFilesDir(null), "bepinex_launcher.log"))
            addFileIfPresent(zip, "launcher/logcat_previous.txt",
                File(context.getExternalFilesDir(null), "logcat.txt"))

            val gameRoot = BepInExPaths.getGameRootDir(packageName)
            if (gameRoot.isDirectory) {
                gameRoot.walkTopDown()
                    .filter { it.isFile && it.length() <= MAX_FILE_BYTES && it.name in logNames }
                    .forEach { file ->
                        val relative = file.relativeTo(gameRoot).path.replace(File.separatorChar, '/')
                        addFileIfPresent(zip, "game/$relative", file)
                    }
            }
        }
        return output
    }

    private fun addCommand(zip: ZipOutputStream, name: String, command: Array<String>) {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            addText(zip, name, "Unable to run command: ${e.message}")
            return
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }.also {
                process.waitFor(10, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            "Command failed: ${e.message}"
        } finally {
            process.destroy()
        }
        addText(zip, name, output)
    }

    private fun addFileIfPresent(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

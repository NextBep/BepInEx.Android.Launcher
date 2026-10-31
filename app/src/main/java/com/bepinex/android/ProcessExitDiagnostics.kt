package com.bepinex.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Reads the OS-owned exit record, which survives native crashes and process death.
 *
 * Ported from the PVZRH launcher so crash reports include the reason the game
 * process (or the launcher itself) went away, not just whatever the logs said.
 */
object ProcessExitDiagnostics {
    private const val MAX_TRACE_BYTES = 512 * 1024

    data class ExitRecord(
        val processName: String,
        val timestamp: Long,
        val reason: Int,
        val status: Int,
        val description: String,
        val importance: Int,
        val pss: Long,
        val rss: Long,
        val trace: String
    ) {
        val isAbnormal: Boolean
            get() = reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR ||
                reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                reason == ApplicationExitInfo.REASON_SIGNALED

        /** Short, user-facing label for the exit reason. */
        val reasonLabel: String
            get() = reasonName(reason)

        fun format(): String = buildString {
            appendLine("process=$processName")
            appendLine("time=${Date(timestamp)} ($timestamp)")
            appendLine("reason=${reasonName(reason)} ($reason)")
            appendLine("status=$status")
            appendLine("description=$description")
            appendLine("importance=$importance")
            appendLine("pss=$pss")
            appendLine("rss=$rss")
            if (trace.isNotBlank()) {
                appendLine()
                appendLine("--- system trace ---")
                append(trace.trimEnd())
            }
        }.trimEnd()
    }

    fun read(
        context: Context,
        sinceMillis: Long = 0L,
        processName: String? = null,
        maxRecords: Int = 16
    ): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
                .asSequence()
                .filter { it.timestamp >= sinceMillis }
                .filter { processName == null || it.processName == processName }
                .map { info ->
                    ExitRecord(
                        processName = info.processName,
                        timestamp = info.timestamp,
                        reason = info.reason,
                        status = info.status,
                        description = info.description.orEmpty(),
                        importance = info.importance,
                        pss = info.pss,
                        rss = info.rss,
                        trace = readTrace(info)
                    )
                }
                .sortedByDescending { it.timestamp }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Latest abnormal exit of [processName] (e.g. `com.bepinex.android:game`) since [sinceMillis]. */
    fun latestAbnormal(context: Context, sinceMillis: Long, processName: String?): ExitRecord? =
        read(context, sinceMillis, processName).firstOrNull { it.isAbnormal }

    fun format(records: List<ExitRecord>): String = records
        .joinToString("\n\n====================\n\n") { it.format() }

    private fun readTrace(info: ApplicationExitInfo): String {
        return try {
            info.traceInputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var remaining = MAX_TRACE_BYTES
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toString(Charsets.UTF_8.name()) +
                    if (remaining == 0) "\n[trace truncated at $MAX_TRACE_BYTES bytes]" else ""
            }.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
}

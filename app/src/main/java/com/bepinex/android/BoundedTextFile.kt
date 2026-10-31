package com.bepinex.android

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Reads only a bounded window of a text file.
 *
 * Logs and previews keep the tail. Editable files at or under [LIMIT_BYTES]
 * are read whole. A larger editable file must stay read-only: saving a tail
 * would erase the unread prefix.
 */
object BoundedTextFile {
    const val LIMIT_BYTES = 256L * 1024L

    data class Snapshot(
        val text: String,
        val truncated: Boolean,
        val fileLength: Long,
        val exists: Boolean
    )

    fun read(file: File, limitBytes: Long = LIMIT_BYTES): Snapshot {
        if (!file.isFile) return Snapshot("", truncated = false, fileLength = 0L, exists = false)
        val length = file.length()
        if (length <= limitBytes) {
            return Snapshot(
                text = if (length == 0L) "" else decodeTail(readRange(file, 0L, length), skipPartialLine = false),
                truncated = false,
                fileLength = length,
                exists = true
            )
        }
        val start = length - limitBytes
        return Snapshot(
            text = decodeTail(readRange(file, start, length), skipPartialLine = startsMidLine(file, start)),
            truncated = true,
            fileLength = length,
            exists = true
        )
    }

    /**
     * Remembers the last file position and reads only the bytes appended since
     * the previous call. A shrink or a jump larger than [LIMIT_BYTES] reads the
     * tail again.
     */
    class Follower(private val limitBytes: Long = LIMIT_BYTES) {
        private var path: String? = null
        private var consumed = -1L
        private var window = ByteArray(0)
        private var snapshot = Snapshot("", truncated = false, fileLength = 0L, exists = false)

        fun read(file: File): Snapshot = synchronized(this) {
            if (!file.isFile) {
                reset()
                return snapshot
            }
            val length = file.length()
            val sameFile = path == file.absolutePath && consumed >= 0L
            if (!sameFile || length < consumed || !windowMatches(file)) return replaceTail(file, length)
            if (length == consumed) return snapshot
            val delta = length - consumed
            if (delta > limitBytes) return replaceTail(file, length)
            val added = readRange(file, consumed, length)
            val combined = if (window.isEmpty()) added else window + added
            val limit = limitBytes.toInt()
            val partial = combined.size > limit && combined[combined.size - limit - 1] != LINE_FEED
            return store(file, length, combined, skipPartialLine = partial)
        }

        private fun windowMatches(file: File): Boolean {
            if (window.isEmpty()) return true
            val windowStart = consumed - window.size
            if (windowStart < 0L) return false
            val probeLength = minOf(64, window.size).toLong()
            val probe = readRange(file, windowStart, windowStart + probeLength)
            if (probe.size.toLong() != probeLength) return false
            for (index in probe.indices) {
                if (probe[index] != window[index]) return false
            }
            return true
        }

        private fun replaceTail(file: File, length: Long): Snapshot {
            if (length == 0L) {
                path = file.absolutePath
                consumed = 0L
                window = ByteArray(0)
                snapshot = Snapshot("", truncated = false, fileLength = 0L, exists = true)
                return snapshot
            }
            val start = if (length > limitBytes) length - limitBytes else 0L
            val bytes = readRange(file, start, length)
            return store(file, length, bytes, skipPartialLine = startsMidLine(file, start))
        }

        private fun store(file: File, length: Long, bytes: ByteArray, skipPartialLine: Boolean): Snapshot {
            val kept = tailBytes(bytes, limitBytes.toInt(), skipPartialLine)
            path = file.absolutePath
            consumed = length
            window = kept
            snapshot = Snapshot(
                text = decodeTail(kept, skipPartialLine = false),
                truncated = length > limitBytes,
                fileLength = length,
                exists = true
            )
            return snapshot
        }

        private fun reset() {
            path = null
            consumed = -1L
            window = ByteArray(0)
            snapshot = Snapshot("", truncated = false, fileLength = 0L, exists = false)
        }
    }

    fun readRange(file: File, start: Long, endExclusive: Long): ByteArray {
        if (endExclusive <= start) return ByteArray(0)
        val count = (endExclusive - start).coerceAtMost(LIMIT_BYTES).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start.coerceAtLeast(0L))
            val buffer = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = raf.read(buffer, offset, count - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == count) buffer else buffer.copyOf(offset)
        }
    }

    /** True when [start] cuts through a line instead of landing on a new one. */
    fun startsMidLine(file: File, start: Long): Boolean {
        if (start <= 0L) return false
        val previous = readRange(file, start - 1, start)
        return previous.isEmpty() || previous[0] != LINE_FEED
    }

    fun decodeTail(bytes: ByteArray, skipPartialLine: Boolean): String {
        val kept = if (skipPartialLine) tailBytes(bytes, bytes.size, skipPartialLine = true) else bytes
        if (kept.isEmpty()) return ""
        return String(kept, Charsets.UTF_8)
    }

    fun formatByteCount(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        if (safe < 1024) return "$safe B"
        val kb = safe / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    private fun tailBytes(bytes: ByteArray, limit: Int, skipPartialLine: Boolean): ByteArray {
        if (bytes.isEmpty()) return bytes
        val slice = if (bytes.size <= limit) bytes else bytes.copyOfRange(bytes.size - limit, bytes.size)
        var start = 0
        if (skipPartialLine) {
            val newline = slice.indexOf(LINE_FEED)
            if (newline >= 0 && newline < slice.lastIndex) start = newline + 1
        }
        while (start < slice.size && (slice[start].toInt() and 0xC0) == 0x80) start++
        if (start == 0) return slice
        if (start >= slice.size) return ByteArray(0)
        return slice.copyOfRange(start, slice.size)
    }

    private const val LINE_FEED = 0x0A.toByte()
}

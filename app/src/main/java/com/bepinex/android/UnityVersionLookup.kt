package com.bepinex.android

import java.io.File
import java.io.RandomAccessFile

/** Reads the Unity editor version embedded in common Unity data files. */
object UnityVersionLookup {
    private const val MAX_VERSION_LENGTH = 32
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:[abcfp]\\d+|rc\\d+)?$")

    private val candidates = linkedMapOf(
        "globalgamemanagers" to intArrayOf(0x14, 0x30),
        "data.unity3d" to intArrayOf(0x12),
        "mainData" to intArrayOf(0x14)
    )

    fun find(dataFolder: File): String? {
        for ((fileName, offsets) in candidates) {
            val file = File(dataFolder, fileName)
            if (!file.isFile) continue

            try {
                RandomAccessFile(file, "r").use { reader ->
                    for (offset in offsets) {
                        if (offset < 0 || offset >= reader.length()) continue
                        reader.seek(offset.toLong())
                        val value = readAscii(reader)
                        if (value != null && versionPattern.matches(value)) return value
                    }
                }
            } catch (e: Exception) {
                BepInExLog.d("Unity version lookup failed for ${file.absolutePath}: ${e.message}")
            }
        }
        return null
    }

    private fun readAscii(reader: RandomAccessFile): String? {
        val value = StringBuilder(MAX_VERSION_LENGTH)
        for (index in 0 until MAX_VERSION_LENGTH) {
            val byte = reader.read()
            if (byte < 0 || byte == 0) break
            if (byte < 0x20 || byte > 0x7e) break
            value.append(byte.toChar())
        }
        return value.toString().trim().ifEmpty { null }
    }

    fun normalize(version: String): String =
        Regex("^(\\d+\\.\\d+\\.\\d+)").find(version.trim())?.groupValues?.get(1)
            ?: version.trim()
}

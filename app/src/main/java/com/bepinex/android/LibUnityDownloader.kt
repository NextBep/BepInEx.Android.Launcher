package com.bepinex.android

import com.bepinex.android.log.BepInExLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads unstripped libunity.so from NextBep/AndroidNativeLibraries.
 *
 * Modeled after FusionCore's LibUnityDownloader.java. The unstripped
 * libunity contains debug symbols that make stack traces readable.
 *
 * Cache key = version + "|" + abi (e.g. "2022.3.62|arm64-v8a").
 * Files are stored in {appDataDir}/libunity/{version}-{abi}/libunity.so.
 */
object LibUnityDownloader {

    private const val TAG = "LibUnityDownloader"
    private const val RAW_BASE_URL =
        "https://raw.githubusercontent.com/NextBep/AndroidNativeLibraries/main/"
    private const val PROXY_BASE_URL =
        "https://gh-proxy.org/https://raw.githubusercontent.com/NextBep/AndroidNativeLibraries/main/"

    /**
     * Ensures an unstripped libunity.so is available for the given Unity version + ABI.
     *
     * @param outputDir  directory to store the downloaded lib (e.g. {appDataDir}/libunity)
     * @param version    Unity version string (e.g. "2022.3.62")
     * @param abi        ABI string (e.g. "arm64-v8a")
     * @param onProgress progress callback
     * @return true if a valid libunity.so is ready at {outputDir}/{version}-{abi}/libunity.so
     */
    fun ensureLibUnity(
        outputDir: File,
        version: String,
        abi: String,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        val targetDir = File(outputDir, "$version-$abi")
        val libFile = File(targetDir, "libunity.so")

        // Check if already cached and valid
        if (libFile.exists() && libFile.length() > 1024 * 1024) {
            BepInExLog.i("$TAG: Unstripped libunity already cached (${formatSize(libFile.length())})")
            onProgress("Unstripped libunity cached")
            return true
        }

        // Clean up any partial download
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        // Normalize version (strip f1/p1 suffix) and ABI
        val normalizedVersion = normalizeVersion(version)
        val normalizedAbi = normalizeAbi(abi)

        // Try download from primary then proxy
        val sources = listOf(
            "${RAW_BASE_URL}${normalizedVersion}/${normalizedAbi}/libunity.so",
            "${PROXY_BASE_URL}${normalizedVersion}/${normalizedAbi}/libunity.so"
        )

        for (url in sources) {
            if (downloadFile(libFile, url, onProgress)) {
                // Verify it's a valid ELF shared library
                if (isValidElf(libFile)) {
                    BepInExLog.i("$TAG: Unstripped libunity downloaded and validated (${formatSize(libFile.length())})")
                    return true
                } else {
                    BepInExLog.w("$TAG: Downloaded file is not a valid ELF — trying next source")
                    libFile.delete()
                }
            }
        }

        BepInExLog.w("$TAG: Failed to download unstripped libunity from all sources")
        targetDir.deleteRecursively()
        return false
    }

    /**
     * Normalizes Unity version by stripping the trailing tag (f1, p1, etc.)
     * e.g. "2022.3.62f3" -> "2022.3.62"
     */
    private fun normalizeVersion(version: String): String {
        return version.replace(Regex("[fp]\\d+$"), "")
    }

    /**
     * Normalizes ABI string to match the AndroidNativeLibraries repository structure.
     * e.g. "arm64-v8a" stays as-is
     */
    private fun normalizeAbi(abi: String): String {
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64-v8a"
            abi.contains("arm") || abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86_64") || abi.contains("x64") -> "x86_64"
            abi.contains("x86") || abi.contains("i686") -> "x86"
            else -> abi
        }
    }

    /**
     * Checks if a file starts with the ELF magic bytes (7f 45 4c 46).
     */
    private fun isValidElf(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val magic = ByteArray(4)
                stream.read(magic) == 4 &&
                    magic[0] == 0x7f.toByte() &&
                    magic[1] == 0x45.toByte() && // 'E'
                    magic[2] == 0x4c.toByte() && // 'L'
                    magic[3] == 0x46.toByte()     // 'F'
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadFile(
        destFile: File,
        url: String,
        onProgress: (String) -> Unit
    ): Boolean {
        BepInExLog.i("$TAG: Downloading $url")
        onProgress("Connecting to server...")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }

            if (connection.responseCode != 200) {
                BepInExLog.w("$TAG: HTTP ${connection.responseCode} for $url")
                return false
            }

            val totalBytes = connection.contentLength
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val pct = (totalRead * 100 / totalBytes).toInt()
                            onProgress("Downloading: $pct%")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            BepInExLog.w("$TAG: Download failed: ${e.message}")
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}

package com.bepinex.android

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Downloads unstripped libunity.so from NextBep/AndroidNativeLibraries.
 *
 * Modeled after FusionCore's LibUnityDownloader.java. The unstripped
 * libunity contains debug symbols that make stack traces readable.
 *
 * Downloads both libunity.so and libunity.sym.so companion.
 * Uses atomic download (temp file + rename) to prevent corruption.
 * Cache metadata stored in cache.properties alongside files.
 */
object LibUnityDownloader {

    private const val TAG = "LibUnityDownloader"
    private const val RAW_BASE_URL =
        "https://raw.githubusercontent.com/NextBep/AndroidNativeLibraries/main/"
    private const val PROXY_BASE_URL =
        "https://gh-proxy.org/https://raw.githubusercontent.com/NextBep/AndroidNativeLibraries/main/"
    private const val MIN_LIB_SIZE = 1024 * 1024 // 1 MB minimum for valid libunity
    private const val MIN_SYM_SIZE = 1024        // 1 KB minimum for sym file
    private const val CACHE_META_FILE = "cache.properties"

    /**
     * Structured progress listener for download operations.
     */
    interface DownloadProgressListener {
        fun onDownloadStarted(url: String, totalBytes: Long)
        fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long)
        fun onDownloadFinished(success: Boolean, usedCache: Boolean)
    }

    /**
     * Thread-safe wrapper around [downloadAndCache]. Runs the download on a
     * worker thread and blocks the caller until completion via [FutureTask].
     */
    fun downloadAndCacheSafely(
        outputDir: File,
        version: String,
        abi: String,
        listener: DownloadProgressListener? = null
    ): Boolean {
        val task = FutureTask { downloadAndCache(outputDir, version, abi, listener) }
        val worker = Thread(task, "LibUnityDownload")
        worker.start()

        return try {
            task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            BepInExLog.e("$TAG: Download thread was interrupted", e)
            false
        } catch (e: ExecutionException) {
            BepInExLog.e("$TAG: Download failed", e.cause ?: e)
            false
        }
    }

    /**
     * Core download logic. Downloads libunity.so and libunity.sym.so atomically.
     * Uses dual sources (raw GitHub + gh-proxy.org) with ELF validation.
     */
    fun downloadAndCache(
        outputDir: File,
        version: String,
        abi: String,
        listener: DownloadProgressListener? = null
    ): Boolean {
        if (version.isBlank()) {
            BepInExLog.e("$TAG: Invalid arguments (outputDir=$outputDir, version='$version')")
            notifyFinished(listener, false, false)
            return false
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            BepInExLog.e("$TAG: Failed to create output directory: ${outputDir.absolutePath}")
            notifyFinished(listener, false, false)
            return false
        }

        val normalizedAbi = normalizeAbi(abi)
        if (normalizedAbi == null) {
            BepInExLog.e("$TAG: Target game ABI is missing or unsupported: $abi")
            notifyFinished(listener, false, false)
            return false
        }

        val normalizedVersion = normalizeVersion(version)

        val libFile = File(outputDir, "libunity.so")
        val symFile = File(outputDir, "libunity.sym.so")
        val cacheMetaFile = File(outputDir, CACHE_META_FILE)

        val cacheKey = "$normalizedVersion|$normalizedAbi"
        if (isCachedLibUnityValid(libFile, symFile, cacheMetaFile, cacheKey)) {
            BepInExLog.i("$TAG: Using cached libunity ($cacheKey) at ${outputDir.absolutePath}")
            notifyFinished(listener, true, true)
            return true
        }

        val sources = listOf(
            "${RAW_BASE_URL}${normalizedVersion}/${normalizedAbi}",
            "${PROXY_BASE_URL}${normalizedVersion}/${normalizedAbi}"
        )

        // Download libunity.so
        var libDownloaded = false
        for (baseUrl in sources) {
            val url = "$baseUrl/libunity.so"
            if (downloadFileAtomic(libFile, url, listener)) {
                if (isValidElf(libFile)) {
                    libDownloaded = true
                    BepInExLog.i("$TAG: libunity.so downloaded and validated (${formatSize(libFile.length())})")
                    break
                } else {
                    BepInExLog.w("$TAG: Downloaded libunity.so is not a valid ELF — trying next source")
                    libFile.delete()
                }
            }
        }

        if (!libDownloaded) {
            BepInExLog.w("$TAG: Failed to download libunity.so from all sources")
            notifyFinished(listener, false, false)
            return false
        }

        // Download libunity.sym.so companion (best effort)
        for (baseUrl in sources) {
            val url = "$baseUrl/libunity.sym.so"
            if (downloadFileAtomic(symFile, url, listener)) {
                if (symFile.length() > MIN_SYM_SIZE) {
                    BepInExLog.i("$TAG: libunity.sym.so downloaded (${formatSize(symFile.length())})")
                } else {
                    BepInExLog.w("$TAG: libunity.sym.so too small, discarding")
                    symFile.delete()
                }
                break
            }
        }

        // Write cache metadata
        try {
            Properties().apply {
                setProperty("cacheKey", cacheKey)
                setProperty("version", version)
                setProperty("normalizedVersion", normalizedVersion)
                setProperty("abi", normalizedAbi)
                setProperty("libunitySize", libFile.length().toString())
                setProperty("libunitySymSize", if (symFile.exists()) symFile.length().toString() else "0")
                setProperty("timestamp", System.currentTimeMillis().toString())
            }.store(cacheMetaFile.outputStream(), "libunity cache metadata")
        } catch (e: Exception) {
            BepInExLog.w("$TAG: Failed to write cache metadata: ${e.message}")
        }

        BepInExLog.i("$TAG: Successfully cached libunity at ${outputDir.absolutePath}")
        notifyFinished(listener, true, false)
        return true
    }

    /**
     * Ensures an unstripped libunity.so is available for the given Unity version + ABI.
     * Simplified wrapper for callers who don't need structured progress.
     */
    fun ensureLibUnity(
        outputDir: File,
        version: String,
        abi: String,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        val targetDir = File(outputDir, "$version-${normalizeAbi(abi)}")
        val libFile = File(targetDir, "libunity.so")
        if (libFile.exists() && libFile.length() > MIN_LIB_SIZE) {
            onProgress("Unstripped libunity cached")
            return true
        }

        return downloadAndCacheSafely(
            targetDir, version, abi,
            object : DownloadProgressListener {
                override fun onDownloadStarted(url: String, totalBytes: Long) {
                    onProgress("Connecting...")
                }
                override fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
                    if (totalBytes > 0) {
                        val pct = (downloadedBytes * 100 / totalBytes).toInt()
                        onProgress("Downloading: $pct%")
                    } else {
                        onProgress("Downloading: ${formatSize(downloadedBytes)}")
                    }
                }
                override fun onDownloadFinished(success: Boolean, usedCache: Boolean) {
                    if (success) onProgress("Unstripped libunity ready")
                    else onProgress("Failed to download unstripped libunity")
                }
            }
        )
    }

    /**
     * Checks if the cache is valid for a given version + ABI.
     */
    fun isCacheValid(outputDir: File, version: String, abi: String): Boolean {
        val normalizedAbi = normalizeAbi(abi) ?: return false
        val normalizedVersion = normalizeVersion(version)
        val targetDir = File(outputDir, "$normalizedVersion-$normalizedAbi")
        val libFile = File(targetDir, "libunity.so")
        return libFile.exists() && libFile.length() > MIN_LIB_SIZE
    }

    /**
     * Returns cache metadata if available.
     */
    fun getCacheInfo(outputDir: File, version: String, abi: String): Map<String, String>? {
        val normalizedAbi = normalizeAbi(abi) ?: return null
        val normalizedVersion = normalizeVersion(version)
        val cacheProps = File(outputDir, "$normalizedVersion-$normalizedAbi/$CACHE_META_FILE")
        return if (cacheProps.exists()) {
            try {
                val props = Properties()
                props.load(cacheProps.inputStream())
                props.stringPropertyNames().associateWith { props.getProperty(it) }
            } catch (e: Exception) { null }
        } else null
    }

    // ---- Cache validation ----

    private fun isCachedLibUnityValid(
        libFile: File, symFile: File, cacheMetaFile: File, expectedCacheKey: String
    ): Boolean {
        if (!libFile.exists() || !libFile.isFile || libFile.length() <= 0) return false
        if (!symFile.exists() || !symFile.isFile || symFile.length() <= 0) return false
        if (!cacheMetaFile.exists() || !cacheMetaFile.isFile) return false

        val meta = Properties()
        try {
            FileInputStream(cacheMetaFile).use { meta.load(it) }
        } catch (e: IOException) {
            BepInExLog.w("$TAG: Failed reading libunity cache metadata: ${e.message}")
            return false
        }

        val actualKey = meta.getProperty("cacheKey", "")
        if (expectedCacheKey != actualKey) return false

        return try {
            val expectedSize = meta.getProperty("libunitySize", "0").toLong()
            val expectedSymSize = meta.getProperty("libunitySymSize", "0").toLong()
            expectedSize > 0 && expectedSize == libFile.length() &&
                expectedSymSize > 0 && expectedSymSize == symFile.length()
        } catch (e: NumberFormatException) {
            BepInExLog.w("$TAG: Invalid libunity cache metadata size: ${e.message}")
            false
        }
    }

    // ---- Normalization ----

    /**
     * Normalizes Unity version by stripping the trailing tag (f1, p1, etc.)
     * e.g. "2022.3.62f3" -> "2022.3.62"
     */
    private fun normalizeVersion(version: String): String {
        return version.trim().replace(Regex("[fp]\\d+$"), "")
    }

    /**
     * Normalizes ABI string to match the AndroidNativeLibraries repository structure.
     * Handles path prefixes, slashes, and common variations.
     */
    private fun normalizeAbi(abi: String): String? {
        if (abi.isBlank()) return null

        var normalized = abi.trim().lowercase()

        // Strip path prefixes (e.g. "lib/arm64-v8a" -> "arm64-v8a")
        val slash = normalized.lastIndexOf('/')
        if (slash in 0 until normalized.length - 1) {
            normalized = normalized.substring(slash + 1)
        }
        val backslash = normalized.lastIndexOf('\\')
        if (backslash in 0 until normalized.length - 1) {
            normalized = normalized.substring(backslash + 1)
        }

        return when (normalized) {
            "arm64", "aarch64", "arm64-v8a" -> "arm64-v8a"
            "armeabi-v7a", "armeabi", "armv7" -> "armeabi-v7a"
            "x86_64", "x64" -> "x86_64"
            "x86", "i686" -> "x86"
            else -> null
        }
    }

    // ---- Download ----

    /**
     * Atomic download: writes to a .download temp file, then renames on success.
     * Prevents partial/corrupt files from being used as cache.
     */
    private fun downloadFileAtomic(
        destFile: File,
        url: String,
        listener: DownloadProgressListener?
    ): Boolean {
        BepInExLog.i("$TAG: Downloading $url")
        val tempFile = File(destFile.parent, "${destFile.name}.download")
        tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val statusCode = connection.responseCode
            if (statusCode < 200 || statusCode >= 300) {
                BepInExLog.w("$TAG: HTTP $statusCode for $url")
                return false
            }

            val totalBytes = connection.contentLengthLong
            notifyStarted(listener, url, totalBytes)

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var lastProgressMs = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= 120L) {
                            notifyProgress(listener, downloadedBytes, totalBytes)
                            lastProgressMs = now
                        }
                    }
                    // Final progress update
                    notifyProgress(listener, downloadedBytes, totalBytes)
                }
            }

            // Atomic rename
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                BepInExLog.w("$TAG: Failed to rename temp file — copying instead")
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            return true
        } catch (e: Exception) {
            BepInExLog.w("$TAG: Download failed: ${e.message}")
            tempFile.delete()
            return false
        } finally {
            connection?.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Public wrapper for ABI normalization. Used by BootstrapActivity to get
     * the normalized ABI string for directory naming.
     */
    fun normalizeAbiPublic(abi: String): String = normalizeAbi(abi) ?: "arm64-v8a"

    // ---- Helpers ----

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

    private fun notifyStarted(listener: DownloadProgressListener?, url: String, totalBytes: Long) {
        listener?.onDownloadStarted(url, totalBytes)
    }

    private fun notifyProgress(listener: DownloadProgressListener?, downloadedBytes: Long, totalBytes: Long) {
        listener?.onDownloadProgress(downloadedBytes, totalBytes)
    }

    private fun notifyFinished(listener: DownloadProgressListener?, success: Boolean, usedCache: Boolean) {
        listener?.onDownloadFinished(success, usedCache)
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

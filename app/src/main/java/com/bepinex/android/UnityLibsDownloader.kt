package com.bepinex.android

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Unity base libraries using Android's [HttpURLConnection].
 *
 * ## Why this exists
 *
 * BepInEx's managed `Il2CppInteropManager.DownloadUnityAssemblies()` downloads
 * the same ZIP via .NET's `HttpClient`. On Android 16,
 * `libSystem.Security.Cryptography.Native.Android.so` crashes with a NULL-pointer
 * dereference in `AndroidCryptoNative_SSLStreamCreate` when called from a .NET
 * ThreadPool worker.
 *
 * By downloading the ZIP with Android's own HTTP stack **before** BepInEx starts,
 * the managed code finds the cached file and skips the broken .NET download path.
 *
 * Important: we only download the ZIP  -- BepInEx handles extraction itself
 * (see `Il2CppInteropManager.DownloadUnityAssemblies` lines 293-295).
 *
 * The primary source is `unity.bepinex.dev` (managed DLLs). NextBep/AndroidNativeLibraries
 * provides **native** `.so` files and must NOT be used for this slot.
 */
object UnityLibsDownloader {

    private const val TAG = "UnityLibsDownloader"
    private const val BEPINEX_BASE_URL = "https://unity.bepinex.dev/libraries/"
    private const val BEPINEX_PROXY_URL =
        "https://gh-proxy.org/https://unity.bepinex.dev/libraries/"

    /**
     * Ensure the unity base libraries ZIP is cached in [outputDir].
     *
     * BepInEx checks for `{outputDir}/{version}.zip` before attempting to
     * download via .NET HttpClient. If the file exists, BepInEx extracts it
     * and skips the broken download path.
     *
     * @param outputDir  e.g. `{bepInExDir}/unity-libs/`
     * @param version    Unity version string, e.g. `2022.3.62`
     * @return true if the ZIP is ready (cached or freshly downloaded)
     */
    fun ensureLibraries(outputDir: File, version: String, onProgress: (String) -> Unit = {}): Boolean {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            BepInExLog.e("$TAG: Failed to create unity-libs directory: ${outputDir.absolutePath}")
            return false
        }

        val downloadVersion = UnityVersionLookup.normalize(version)
        if (version != downloadVersion) {
            BepInExLog.i("$TAG: Normalized Unity version for download: $version  -> $downloadVersion")
        }

        val zipFile = File(outputDir, "$downloadVersion.zip")
        if (zipFile.exists() && zipFile.length() > 1024 && zipLooksValid(zipFile)) {
            BepInExLog.i("$TAG: Unity base libraries already cached (${formatSize(zipFile.length())})")
            onProgress("Unity libraries cached")
            return true
        }

        // Cached file is corrupt or wrong artifact — remove it
        zipFile.delete()

        // Primary source: unity.bepinex.dev (managed DLLs — the correct artifact)
        // Fallback: gh-proxy mirror for China-reachable downloads
        val sources = listOf(
            "${BEPINEX_BASE_URL}${downloadVersion}.zip",
            "${BEPINEX_PROXY_URL}${downloadVersion}.zip"
        )
        return sources.any { url -> downloadZip(zipFile, url, downloadVersion, onProgress) }
    }

    /**
     * Validates that a ZIP contains managed Unity assemblies (*.dll) and not
     * native libraries (*.so). The unity-libs slot is consumed by BepInEx's
     * Il2CppInteropManager which expects UnityEngine.*.dll files.
     */
    private fun zipLooksValid(zipFile: File): Boolean {
        return try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val hasDll = entries.any { it.name.endsWith(".dll") && !it.isDirectory }
                val hasNativeLib = entries.any {
                    it.name.matches(Regex(".*/lib.*\\.so")) && !it.isDirectory
                }
                if (hasNativeLib && !hasDll) {
                    BepInExLog.w("$TAG: ZIP contains native .so but no managed DLLs — wrong artifact")
                    false
                } else {
                    hasDll
                }
            }
        } catch (e: Exception) {
            BepInExLog.w("$TAG: Cannot validate ZIP content: ${e.message}")
            false
        }
    }

    private fun downloadZip(
        destFile: File,
        url: String,
        version: String,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        BepInExLog.i("$TAG: Downloading $url")
        onProgress("Connecting to server...")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 300_000  // 5 min  -- ZIP can be 100+ MB
                instanceFollowRedirects = true
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                BepInExLog.w("$TAG: HTTP $status for $url")
                onProgress("Trying another library source...")
                return false
            }

            val totalBytes = connection.contentLengthLong
            val totalStr = formatSize(totalBytes)
            BepInExLog.i("$TAG: Downloading $totalStr...")
            onProgress("0 / $totalStr")

            // Download to temp file, then rename atomically
            val tempFile = File(destFile.parentFile, "${destFile.name}.download")
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastProgress = 0L
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        val now = System.currentTimeMillis()
                        if (now - lastProgress > 500) {
                            val pct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else 0
                            onProgress("$pct%  ${formatSize(downloaded)} / $totalStr")
                            lastProgress = now
                        }
                    }
                }
            }

            if (tempFile.length() < 4 || !tempFile.renameTo(destFile)) {
                BepInExLog.e("$TAG: Failed to rename temp file to ${destFile.name}")
                tempFile.delete()
                return false
            }

            BepInExLog.i("$TAG: Download complete for Unity $version (${formatSize(destFile.length())})")
            return true

        } catch (e: Exception) {
            BepInExLog.e("$TAG: Download failed", e)
            destFile.delete()
            return false
        } finally {
            connection?.disconnect()
            val tempFile = File(destFile.parentFile, "${destFile.name}.download")
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "? B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}

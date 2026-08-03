package com.bepinex.android

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Auto-detects Unity IL2CPP games installed on the device.
 *
 * Uses [PackageManager.queryIntentActivities] with MAIN/LAUNCHER intent
 * to discover launchable apps without requiring QUERY_ALL_PACKAGES permission.
 * The corresponding `<queries>` declaration in AndroidManifest.xml makes this
 * work on Android 11+ without any runtime permission dialogs.
 *
 * Detection algorithm:
 *   1. Query all launchable activities via PackageManager
 *   2. Deduplicate by package name, skip system + our own app
 *   3. For each candidate APK, open as ZIP and check for libil2cpp.so
 */
object GameDetector {

    private const val TAG = "GameDetector"

    /** ABI directories to scan for libil2cpp.so */
    private val UNITY_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** Cached detection results */
    private var cachedGames: List<DetectedGame>? = null

    data class DetectedGame(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val versionName: String,
        val versionCode: Long,
        val unityVersion: String?,
        val apkPath: String
    )

    /**
     * Scan installed apps and return detected Unity IL2CPP games.
     */
    suspend fun detectGames(context: Context): List<DetectedGame> = withContext(Dispatchers.IO) {
        cachedGames?.let { return@withContext it }

        val pm = context.packageManager
        val results = mutableListOf<DetectedGame>()
        val seenPackages = mutableSetOf<String>()

        // Query all launchable activities (works with <queries> intent filter)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)

        BepInExLog.i("Scanning ${activities.size} launchable apps for Unity IL2CPP games...")

        for (resolveInfo in activities) {
            val packageName = resolveInfo.activityInfo.packageName

            // Deduplicate (some apps have multiple launcher activities)
            if (packageName in seenPackages) continue
            seenPackages.add(packageName)

            // Skip our own package
            if (packageName == context.packageName) continue

            // Get app info
            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }

            // Skip system apps for performance
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

            val apkPath = appInfo.sourceDir ?: continue
            if (!File(apkPath).exists()) continue

            // Check for libil2cpp.so in the APK
            val hasIl2Cpp = checkForIl2Cpp(apkPath)
            if (!hasIl2Cpp) {
                // Also try the native library directory
                val libCheck = File(appInfo.nativeLibraryDir, "libil2cpp.so")
                if (!libCheck.exists()) continue
            }

            // Found a Unity IL2CPP game!
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }

            var versionName = "Unknown"
            var versionCode = 0L
            try {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(packageName, 0)
                versionName = pkgInfo.versionName ?: "Unknown"
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to get package info for $packageName: ${e.message}")
            }

            val game = DetectedGame(
                packageName = packageName,
                label = label,
                icon = icon,
                versionName = versionName,
                versionCode = versionCode,
                unityVersion = null,
                apkPath = apkPath
            )

            BepInExLog.i("Detected: $label ($packageName) v$versionName")
            results.add(game)
        }

        BepInExLog.i("Found ${results.size} Unity IL2CPP game(s)")

        cachedGames = results
        results
    }

    /**
     * Open an APK as a ZIP and check for libil2cpp.so in any ABI directory.
     */
    private fun checkForIl2Cpp(apkPath: String): Boolean {
        try {
            ZipFile(apkPath).use { zip ->
                for (abi in UNITY_ABIS) {
                    val entry = zip.getEntry("lib/$abi/libil2cpp.so")
                    if (entry != null && !entry.isDirectory) return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot scan APK: $apkPath — ${e.message}")
        }
        return false
    }

    fun invalidateCache() {
        cachedGames = null
    }
}

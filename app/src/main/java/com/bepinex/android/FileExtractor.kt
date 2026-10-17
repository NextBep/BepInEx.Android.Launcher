package com.bepinex.android

import android.content.Context
import android.os.Build
import com.bepinex.android.tools.Utilities
import java.io.File

/**
 * Extracts the BepInEx and .NET runtimes from APK assets.
 *
 * Layout (matches the bootstrap flow):
 *   - BepInEx runtime: external per-game directory ([BepInExPaths.getBepInExDir]),
 *     `config/` preserved across re-extraction so user config survives updates
 *   - .NET runtime: shared app-private directory (`getDir("bepinex_runtime")/dotnet`),
 *     skipped while the (versionCode | assetSize) stamp matches
 */
class FileExtractor(private val context: Context) {

    companion object {
        private const val TAG = "FileExtractor"
        private const val RUNTIME_DIR_NAME = "bepinex_runtime"
        private const val BEPINEX_ASSET = "BepInEx.Android.zip"
        private const val DOTNET_ASSET = "dotnet.zip"
        private const val DOTNET_STAMP = ".extract.stamp"
    }

    /** Shared .NET runtime directory (not per-game). */
    private fun dotnetDir(): File =
        File(context.getDir(RUNTIME_DIR_NAME, Context.MODE_PRIVATE), "dotnet")

    /** True when both the BepInEx core and the shared .NET runtime are on disk. */
    fun isFrameworkReady(packageName: String): Boolean {
        val bepInExCore = File(BepInExPaths.getBepInExDir(packageName), "core/BepInEx.Core.dll")
        return bepInExCore.isFile && File(dotnetDir(), "System.Private.CoreLib.dll").isFile
    }

    /**
     * Extracts the BepInEx runtime for a game when the core marker is missing.
     * `config/` is preserved. The bootstrap flow re-extracts on every launch for
     * framework updates; this UI-facing variant only fills in missing files.
     */
    fun extractBepInExIfNeeded(packageName: String, onProgress: (String) -> Unit = {}) {
        val bepInExDir = BepInExPaths.getBepInExDir(packageName)
        val marker = File(bepInExDir, "core/BepInEx.Core.dll")

        if (marker.isFile) {
            BepInExLog.i("$TAG: BepInEx runtime already extracted for $packageName")
            return
        }

        onProgress("Extracting BepInEx…")
        BepInExLog.i("$TAG: Extracting $BEPINEX_ASSET -> ${bepInExDir.absolutePath}")
        Utilities.extractZipFromAssets(context, BEPINEX_ASSET, bepInExDir, "config/")

        if (!marker.isFile) {
            BepInExLog.e("$TAG: BepInEx extraction did not produce ${marker.absolutePath}")
        }
    }

    /**
     * Extracts the shared .NET runtime unless the (versionCode | assetSize) stamp
     * matches — repeat launches then skip the 20+ MB unpack.
     */
    fun extractDotnetIfNeeded(onProgress: (String) -> Unit = {}) {
        val dotnetDir = dotnetDir()
        val stamp = dotnetStamp()
        val stampFile = File(dotnetDir, DOTNET_STAMP)
        val coreLib = File(dotnetDir, "System.Private.CoreLib.dll")

        if (stampFile.isFile && coreLib.isFile && stampFile.readText().trim() == stamp) {
            BepInExLog.i("$TAG: .NET runtime already extracted (stamp unchanged)")
            return
        }

        if (dotnetDir.exists()) {
            BepInExLog.i("$TAG: .NET runtime stamp changed — re-extracting")
            dotnetDir.deleteRecursively()
        }
        dotnetDir.mkdirs()

        onProgress("Extracting .NET runtime…")
        BepInExLog.i("$TAG: Extracting $DOTNET_ASSET -> ${dotnetDir.absolutePath}")
        Utilities.extractZipFromAssets(context, DOTNET_ASSET, dotnetDir)

        try {
            stampFile.writeText(stamp)
        } catch (e: Exception) {
            BepInExLog.w("$TAG: Failed to write .NET extraction stamp: ${e.message}")
        }
    }

    private fun dotnetStamp(): String {
        val versionCode = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
        return "$versionCode|${assetSize(DOTNET_ASSET)}"
    }

    private fun assetSize(assetName: String): Long {
        return try {
            context.assets.openFd(assetName).use { it.length }
        } catch (e: Exception) {
            try {
                context.assets.open(assetName).use { it.available().toLong() }
            } catch (e2: Exception) {
                -1L
            }
        }
    }
}

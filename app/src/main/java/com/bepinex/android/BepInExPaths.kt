package com.bepinex.android

import android.os.Environment
import java.io.File

/**
 * Resolves BepInEx paths for any game package.
 *
 * FusionCore-compatible pattern:
 *   External: /storage/emulated/0/BepInEx_Android/{packageName}/BepInEx/
 *   Internal: {filesDir}/{packageName}/
 *
 * Each game gets its own directory under the BepInEx_Android root on external storage,
 * so mods, configs, and logs are isolated per game.
 */
object BepInExPaths {

    /** Root directory on external storage */
    private const val ROOT_DIR = "BepInEx_Android"

    fun getGameRootDir(packageName: String): File =
        File(Environment.getExternalStorageDirectory(), "$ROOT_DIR/$packageName")

    fun getBepInExDir(packageName: String): File =
        File(getGameRootDir(packageName), "BepInEx")

    fun getPluginsDir(packageName: String): File =
        File(getBepInExDir(packageName), "plugins")

    fun getCoreDir(packageName: String): File =
        File(getBepInExDir(packageName), "core")

    fun getConfigDir(packageName: String): File =
        File(getBepInExDir(packageName), "config")

    fun getInteropDir(packageName: String): File =
        File(getBepInExDir(packageName), "interop")

    fun getLogFile(packageName: String): File =
        File(getBepInExDir(packageName), "LogOutput.log")

    fun getLogsDir(packageName: String): File =
        File(getBepInExDir(packageName), "logs")

    fun getUnityLibsDir(packageName: String): File =
        File(getBepInExDir(packageName), "unity-libs")

    fun getVanillaStateDir(packageName: String): File =
        File(getGameRootDir(packageName), "vanilla")

    fun getModpackDir(packageName: String, modpackName: String): File =
        File(getGameRootDir(packageName), "modpacks/$modpackName")

    fun getModpackConfigDir(packageName: String, modpackName: String): File =
        File(getModpackDir(packageName, modpackName), "config")

    fun getModpackLogsDir(packageName: String, modpackName: String): File =
        File(getModpackDir(packageName, modpackName), "logs")

    fun getModpackLogFile(packageName: String, modpackName: String?): File =
        if (modpackName.isNullOrEmpty()) {
            File(getVanillaStateDir(packageName), "logs/LogOutput.log")
        } else {
            File(getModpackLogsDir(packageName, modpackName), "LogOutput.log")
        }

    /** Internal app-private data directory for a game */
    fun getAppDataDir(filesDir: File, packageName: String): File =
        File(filesDir, packageName)

    fun getDotnetDir(filesDir: File, packageName: String): File =
        File(getAppDataDir(filesDir, packageName), "dotnet")

    fun getCopiedDataDir(filesDir: File, packageName: String): File =
        File(getAppDataDir(filesDir, packageName), "Data_copy")

    /** Check if BepInEx has been extracted for a game */
    fun isBepInExExtracted(packageName: String): Boolean =
        File(getCoreDir(packageName), "BepInEx.Core.dll").exists()

    /** Check if dotnet runtime has been extracted for a game */
    fun isDotnetExtracted(filesDir: File, packageName: String): Boolean =
        File(getDotnetDir(filesDir, packageName), "System.Private.CoreLib.dll").exists()
}

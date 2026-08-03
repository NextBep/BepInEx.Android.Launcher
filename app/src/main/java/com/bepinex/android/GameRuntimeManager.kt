package com.bepinex.android

import android.content.Context
import java.io.File

/**
 * Runtime path resolver using FusionCore-compatible storage layout.
 *
 * Delegates to [BepInExPaths] for all path resolution.
 */
class GameRuntimeManager(private val context: Context) {

    fun getBepInExDir(packageName: String): File =
        BepInExPaths.getBepInExDir(packageName)

    fun getBepInExCoreDir(packageName: String): File =
        BepInExPaths.getCoreDir(packageName)

    fun getBepInExPluginsDir(packageName: String): File =
        BepInExPaths.getPluginsDir(packageName)

    fun getDotnetDir(packageName: String): File =
        BepInExPaths.getDotnetDir(context.filesDir, packageName)

    fun getGameDataDir(packageName: String): File =
        BepInExPaths.getAppDataDir(context.filesDir, packageName)

    fun getGameRootDir(packageName: String): File =
        BepInExPaths.getGameRootDir(packageName)
}

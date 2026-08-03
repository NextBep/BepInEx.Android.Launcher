﻿﻿package com.bepinex.android.fusion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.view.Display
import java.io.File

/**
 * Context wrapper that redirects storage paths and prevents the game
 * from finding its own native libraries.
 *
 * Key redirects:
 *   - filesDir, cacheDir  -> our app's directories
 *   - SharedPreferences  -> our app's preferences
 *   - nativeLibraryDir  -> "" (empty! prevents game from finding its own libs)
 *   - externalFilesDir, externalCacheDir  -> our app's
 *   - getResources  -> game context (so the game can access its own APK resources)
 *
 * The nativeLibraryDir = "" trick is CRITICAL: it prevents the game's
 * UnityPlayer from finding libmain.so in the game APK. Combined with
 * the findLibrary Pine hook (in NativeLibraryManager), we redirect
 * "main"  -> OUR libmain.so from our APK's lib directory.
 *
 * @see CustomContextWrapper.java in FusionCore main branch
 */
class CustomContextWrapper(
    private val fusionContext: Context,
    private val appContext: Context,
    private val gameContext: Context
) : ContextWrapper(gameContext) {

    init {
        // Prevent game from finding its own native libraries
        // The findLibrary hook will redirect to our libs
        this.applicationInfo.nativeLibraryDir = ""
        // Redirect data dir to our app
        this.applicationInfo.dataDir = appContext.applicationInfo.dataDir
    }

    // Resources: the game Activity needs ITS OWN APK resources
    // When a game Activity runs in our process, getResources() would
    // otherwise return OUR resources. The game needs its own resources
    // to access strings, themes, layouts from its own APK.

    override fun getResources(): Resources = gameContext.resources

    override fun getTheme(): Resources.Theme = gameContext.theme

    override fun getAssets(): android.content.res.AssetManager = gameContext.assets

    override fun getClassLoader(): ClassLoader = gameContext.classLoader

    /**
     * CRITICAL: Return the GAME's package name, not ours.
     *
     * Unity's internal code uses [getPackageName] to build resource
     * identifiers via Resources.getIdentifier(). If this returns our
     * package name ("com.bepinex.android"), the resource lookup fails
     * and getIdentifier() returns 0  -> Resources$NotFoundException.
     */
    override fun getPackageName(): String = gameContext.packageName

    /**
     * CRITICAL: Return the GAME's package name for attribution purposes.
     * Required on Android 11+ (API 30+) for proper resource resolution.
     */
    override fun getOpPackageName(): String = gameContext.packageName

    // Storage redirection

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        fusionContext.getSharedPreferences(name, mode)

    override fun getFilesDir(): File = fusionContext.filesDir

    override fun getCacheDir(): File = fusionContext.cacheDir

    override fun getExternalCacheDir(): File? = fusionContext.externalCacheDir

    override fun getExternalCacheDirs(): Array<File> = fusionContext.externalCacheDirs

    override fun getExternalFilesDir(type: String?): File? =
        fusionContext.getExternalFilesDir(type)

    override fun getExternalFilesDirs(type: String?): Array<File> =
        fusionContext.getExternalFilesDirs(type)

    override fun getDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fusionContext.display
        } else null

    override fun getSystemService(name: String): Any? =
        fusionContext.getSystemService(name)

    // CRITICAL: must return the REAL Application, not the Activity.
    // The Activity's mBase is our wrapper, so returning the Activity
    // creates an infinite recursion (wrapper  -> Activity  -> mBase  -> wrapper  -> ...)
    override fun getApplicationContext(): Context = fusionContext.applicationContext

    override fun getObbDir(): File? = fusionContext.obbDir

    override fun getObbDirs(): Array<File> = fusionContext.obbDirs
}

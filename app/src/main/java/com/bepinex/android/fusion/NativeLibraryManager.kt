package com.bepinex.android.fusion

import android.util.Log
import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method

/**
 * Manages native library loading redirects via Pine hooks on
 * ClassLoader.findLibrary().
 *
 * ## How it works (FusionCore main branch)
 *
 * When the game's UnityPlayer constructor calls `System.loadLibrary("main")`,
 * Android's ClassLoader calls `findLibrary("main")` to locate `libmain.so`.
 *
 * Our hook intercepts this call and redirects:
 *   - "main"   → OUR libmain.so (in appLibraryDirectory)
 *   - "fusion" → OUR libfusion.so (in appLibraryDirectory)
 *   - "il2cpp", "unity" → game's libraries (in gameLibraryDirectory)
 *
 * CRITICAL: Because the game's ClassLoader loads our libmain.so, its JNI_OnLoad
 * runs in the GAME's ClassLoader namespace. This means FindClass("com.unity3d.player.NativeLoader")
 * finds the game's REAL NativeLoader class — no stubs needed!
 *
 * @see NativeLibraryManager.java in FusionCore main branch
 */
object NativeLibraryManager {

    private const val TAG = "NativeLibraryManager"

    /** Libraries provided by OUR APK (loaded in game ClassLoader namespace) */
    private val fusionLibraries = mutableListOf<String>()

    /** Libraries from the GAME's APK (redirected to gameLibraryDirectory) */
    private val gameLibraries = mutableListOf<String>()

    /** Unity data libraries (il2cpp, unity) from appDataDirectory */
    private val dataLibraries = mutableListOf<String>()

    /**
     * Register a library name that should be loaded from OUR APK's lib directory.
     * Example: "main" → loads libmain.so from appLibraryDirectory.
     */
    fun addFusionLibrary(name: String) {
        fusionLibraries.add(name)
        BepInExLog.i("Fusion library registered: $name")
    }

    /**
     * Register a library name that should be loaded from the GAME's lib directory.
     * Example: "GameNativeExtra" → loads libGameNativeExtra.so from gameLibraryDirectory.
     */
    fun addGameLibrary(name: String) {
        gameLibraries.add(name)
    }

    /**
     * Register a data library (il2cpp, unity) that should be loaded from appDataDirectory.
     */
    fun addDataLibrary(name: String) {
        dataLibraries.add(name)
        BepInExLog.i("Data library registered: $name")
    }

    /**
     * Install the findLibrary Pine hook.
     *
     * Must be called BEFORE UnityPlayer is constructed, because UnityPlayer's
     * constructor triggers System.loadLibrary("main") which calls findLibrary.
     */
    fun setupLibraryHooks(config: FusionConfig) {
        val findLibraryMethod = findFindLibraryMethod()
            ?: throw IllegalStateException("Cannot find ClassLoader.findLibrary method for hooking")

        BepInExLog.i("Hooking findLibrary: ${findLibraryMethod.declaringClass.name}.${findLibraryMethod.name}")

        Pine.hook(findLibraryMethod, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val libName = callFrame.args[0].toString()
                BepInExLog.i("findLibrary: $libName")  // INFO level for debugging

                // Check fusion libraries first
                for (fusionLib in fusionLibraries) {
                    if (libName == fusionLib) {
                        val path = "${config.appLibraryDirectory}/lib${libName}.so"
                        BepInExLog.i("findLibrary REDIRECT [$libName] → fusion: $path")
                        callFrame.result = path
                        return
                    }
                }

                // Check data libraries
                for (dataLib in dataLibraries) {
                    if (libName == dataLib) {
                        val path = "${config.appDataDirectory}/lib${libName}.so"
                        BepInExLog.i("findLibrary REDIRECT [$libName] → data: $path")
                        callFrame.result = path
                        return
                    }
                }

                // Check game libraries
                for (gameLib in gameLibraries) {
                    if (libName == gameLib) {
                        val path = "${config.gameLibraryDirectory}/lib${libName}.so"
                        BepInExLog.i("findLibrary REDIRECT [$libName] → game: $path")
                        callFrame.result = path
                        return
                    }
                }
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                if (callFrame.hasThrowable()) {
                    Log.w(TAG, "findLibrary threw for ${callFrame.args[0]}", callFrame.throwable)
                }
            }
        })
    }

    /**
     * Find ClassLoader.findLibrary(String) method via reflection.
     * Walks up the ClassLoader hierarchy to find the method.
     */
    private fun findFindLibraryMethod(): Method? {
        var clazz: Class<*>? = NativeLibraryManager::class.java.classLoader?.javaClass

        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod("findLibrary", String::class.java)
                method.isAccessible = true
                BepInExLog.i("Found findLibrary in ${clazz.name}")
                return method
            } catch (e: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }

        return null
    }
}

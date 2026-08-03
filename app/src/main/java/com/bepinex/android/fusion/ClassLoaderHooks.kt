package com.bepinex.android.fusion

import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method

/**
 * Pine hooks on ClassLoader.loadClass() for bidirectional class loading.
 *
 * This is necessary because:
 *   1. Our code needs to load game Activity classes (from game DEX)
 *   2. Game code may need classes from our DEX (UnityPlayer stubs etc.)
 *
 * The hook wraps loadClass() on BOTH classloaders to enable cross-loading
 * when a class is not found in the default loader.
 *
 * @see ClassLoaderHooks.java in FusionCore main branch
 */
object ClassLoaderHooks {

    private const val TAG = "ClassLoaderHooks"

    /**
     * Install the bidirectional loadClass hook.
     *
     * @param gameClassLoader The game's ClassLoader (from createPackageContext with CONTEXT_INCLUDE_CODE)
     */
    fun installHooks(gameClassLoader: ClassLoader) {
        val loadClassMethod = findLoadClassMethod()
            ?: throw IllegalStateException("Cannot find ClassLoader.loadClass method for hooking")

        val myClassLoader = ClassLoaderHooks::class.java.classLoader
            ?: throw IllegalStateException("Our ClassLoader is null")

        BepInExLog.i("Hooking loadClass: ${loadClassMethod.declaringClass.name}.${loadClassMethod.name}")
        BepInExLog.i("  Our loader: ${myClassLoader.javaClass.name}")
        BepInExLog.i("  Game loader: ${gameClassLoader.javaClass.name}")

        Pine.hook(loadClassMethod, object : MethodHook() {
            override fun afterCall(callFrame: Pine.CallFrame) {
                try {
                    // Only attempt fallback if class was not found
                    if (callFrame.throwable !is ClassNotFoundException) return

                    val className = callFrame.args[0] as String
                    @Suppress("UNUSED_VARIABLE")
                    val resolve = callFrame.args[1] as Boolean

                    // If game loader failed, try our loader
                    if (callFrame.thisObject == gameClassLoader) {
                        try {
                            val myClass = callFrame.invokeOriginalMethod(
                                myClassLoader, className, resolve) as Class<*>
                            callFrame.result = myClass
                            BepInExLog.d("loadClass fallback: $className → OUR loader")
                        } catch (e: Exception) {
                            // Not in our loader either — let original exception propagate
                        }
                        return
                    }

                    // If our loader failed, try game loader
                    if (callFrame.thisObject == myClassLoader) {
                        try {
                            val gameClass = callFrame.invokeOriginalMethod(
                                gameClassLoader, className, resolve) as Class<*>
                            callFrame.result = gameClass
                            BepInExLog.d("loadClass fallback: $className → GAME loader")
                        } catch (e: Exception) {
                            // Not in game loader either — let original exception propagate
                        }
                    }
                } catch (e: Exception) {
                    BepInExLog.e("Unexpected error in loadClass hook", e)
                }
            }
        })
    }

    /**
     * Find ClassLoader.loadClass(String, boolean) via reflection.
     */
    private fun findLoadClassMethod(): Method? {
        var clazz: Class<*>? = ClassLoaderHooks::class.java.classLoader?.javaClass

        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType!!)
                method.isAccessible = true
                BepInExLog.i("Found loadClass in ${clazz.name}")
                return method
            } catch (e: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }

        return null
    }
}

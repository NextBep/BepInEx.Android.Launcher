package com.bepinex.android.fusion

import android.content.pm.PackageManager
import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method

/**
 * Pine hooks on PackageManager to suppress PlayCore AssetPackDelivery errors.
 *
 * Some Unity games use Google Play Asset Delivery. When running outside
 * the official Play Store environment, calls to PlayCore components fail
 * with "does not exist" errors. We suppress these errors so the game
 * can continue running.
 *
 * Note: Pine cannot hook abstract methods, so we find the concrete
 * implementation (ApplicationPackageManager) and hook that.
 *
 * @see PackageManagerHooks.java in FusionCore main branch
 */
object PackageManagerHooks {

    private const val PLAY_CORE_PREFIX = "com.google.android.play.core.assetpacks."

    /**
     * Install hooks on PackageManager.setComponentEnabledSetting() to
     * silently swallow calls targeting PlayCore components.
     */
    fun installHooks(packageManager: PackageManager) {
        try {
            // We can't hook the abstract PackageManager method directly.
            // Find the concrete implementation class and hook that.
            val pmClass = packageManager.javaClass
            BepInExLog.i("PM concrete class: ${pmClass.name}")

            // Try the concrete method on the implementation class
            val method = try {
                pmClass.getDeclaredMethod(
                    "setComponentEnabledSetting",
                    android.content.ComponentName::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!
                )
            } catch (e: NoSuchMethodException) {
                // Fallback: find by name
                pmClass.declaredMethods.firstOrNull {
                    it.name == "setComponentEnabledSetting" &&
                    it.parameterTypes.size == 3
                } ?: throw e
            }

            BepInExLog.i("Hooking PM: ${pmClass.name}.${method.name}")

            Pine.hook(method, object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    try {
                        val componentName = callFrame.args[0] as? android.content.ComponentName
                        val className = componentName?.className ?: return

                        if (className.startsWith(PLAY_CORE_PREFIX)) {
                            BepInExLog.d("Suppressing PlayCore: $className")
                            // Swallow the call — return without doing anything
                            callFrame.result = null
                        }
                    } catch (e: Exception) {
                        // Swallow — don't let our hook crash the game
                    }
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    if (callFrame.hasThrowable()) {
                        val throwable = callFrame.throwable
                        if (throwable is IllegalArgumentException &&
                            throwable.message?.contains("does not exist") == true) {
                            BepInExLog.d("Clearing PlayCore exception: ${throwable.message}")
                            // Clear the exception so the game doesn't crash
                            callFrame.setResult(null)
                        }
                    }
                }
            })

            BepInExLog.i("PackageManager hooks installed on ${pmClass.name}")
        } catch (e: Exception) {
            BepInExLog.e("Failed to install PackageManager hooks", e)
        }
    }
}

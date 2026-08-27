package com.bepinex.android.fusion

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.bepinex.android.BepInExLog
import com.bepinex.android.StubActivity
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * Hooks Instrumentation.execStartActivity / newActivity so game launcher
 * activities can start without being declared in AndroidManifest.xml.
 *
 * @see InstrumentationHooks.java in FusionCore
 */
object InstrumentationHooks {

    const val EXTRA_IS_DYNAMIC_ACTIVITY = "bepinex.is_dynamic_activity"
    const val EXTRA_ORIGINAL_INTENT = "bepinex.original_intent"
    const val EXTRA_TARGET_ORIENTATION = "bepinex.target_orientation"

    @Volatile
    var areHooksInstalled = false
        private set

    fun install() {
        if (areHooksInstalled) {
            BepInExLog.d("Instrumentation hooks already installed")
            return
        }

        try {
            hookAllMethodsByName(Instrumentation::class.java, "execStartActivity", object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    handleExecStartBeforeCall(callFrame)
                }
            })

            hookAllMethodsByName(Instrumentation::class.java, "newActivity", object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    handleNewActivityBeforeCall(callFrame)
                }
            })

            hookActivityOnCreate()
            areHooksInstalled = true
            BepInExLog.i("Instrumentation hooks installed")
        } catch (e: Exception) {
            BepInExLog.e("Failed to install Instrumentation hooks", e)
        }
    }

    private fun hookAllMethodsByName(clazz: Class<*>, methodName: String, hook: MethodHook) {
        try {
            for (method in clazz.declaredMethods) {
                if (method.name == methodName) {
                    Pine.hook(method, hook)
                }
            }
        } catch (e: SecurityException) {
            BepInExLog.e("Failed to hook $methodName on ${clazz.name}", e)
        }
    }

    private fun hookActivityOnCreate() {
        val orientationHook = object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val activity = callFrame.thisObject as? Activity ?: return
                applyTargetOrientation(activity)
            }
        }
        Pine.hook(Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java), orientationHook)
        Pine.hook(Activity::class.java.getDeclaredMethod("onResume"), orientationHook)
    }

    private fun applyTargetOrientation(activity: Activity) {
        try {
            val intent = activity.intent ?: return
            val orientation = readTargetOrientation(intent)
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
            activity.requestedOrientation = orientation
            BepInExLog.i("Applied target orientation $orientation to ${activity.javaClass.name}")
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply target orientation", e)
        }
    }

    private fun readTargetOrientation(intent: Intent): Int {
        val orientation = intent.getIntExtra(
            EXTRA_TARGET_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )
        if (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return orientation
        return resolveOriginalIntent(intent)?.getIntExtra(
            EXTRA_TARGET_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun handleExecStartBeforeCall(callFrame: Pine.CallFrame) {
        try {
            val args = callFrame.args ?: return
            val intentIdx = args.indexOfFirst { it is Intent }
            if (intentIdx < 0) {
                BepInExLog.e("No intent found in execStartActivity")
                return
            }

            val intent = args[intentIdx] as? Intent
            if (intent?.component == null) {
                BepInExLog.e("Intent or Intent component was null")
                return
            }

            val targetClass = intent.component!!.className
            if (isDynamicIntent(intent)) return

            args[intentIdx] = getInjectedIntent(intent)
            BepInExLog.d("execStartActivity: intercepted unregistered activity: $targetClass")
        } catch (e: Exception) {
            BepInExLog.e("Error in execStartActivity beforeCall", e)
        }
    }

    private fun handleNewActivityBeforeCall(callFrame: Pine.CallFrame) {
        try {
            val args = callFrame.args ?: return
            var intentIdx = -1
            var strIdx = -1
            for (i in args.indices) {
                when (args[i]) {
                    is Intent -> intentIdx = i
                    is String -> strIdx = i
                }
            }
            if (intentIdx < 0 || strIdx < 0) {
                BepInExLog.e("Intent or String not found in newActivity")
                return
            }

            val intent = args[intentIdx] as Intent
            if (!isDynamicIntent(intent)) return

            val original = resolveOriginalIntent(intent)
            if (original?.component != null) {
                args[intentIdx] = original
                args[strIdx] = original.component!!.className
                BepInExLog.d("newActivity: restored ${original.component!!.className}")
            } else {
                BepInExLog.e("Failed to resolve original intent")
            }
        } catch (e: Exception) {
            BepInExLog.e("Error in newActivity beforeCall", e)
        }
    }

    private fun resolveOriginalIntent(currentIntent: Intent): Intent? {
        return try {
            currentIntent.setExtrasClassLoader(InstrumentationHooks::class.java.classLoader)
            val originalIntent = currentIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_INTENT)
            if (originalIntent?.component != null) {
                BepInExLog.d("Resolved original intent for ${originalIntent.component!!.className}")
                originalIntent
            } else {
                null
            }
        } catch (e: Exception) {
            BepInExLog.e("Error resolving original intent", e)
            null
        }
    }

    private fun getInjectedIntent(intent: Intent): Intent {
        val newIntent = Intent(intent)
        newIntent.putExtra(EXTRA_IS_DYNAMIC_ACTIVITY, true)
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent)
        newIntent.component = ComponentName("com.bepinex.android", StubActivity::class.java.name)
        return newIntent
    }

    private fun isDynamicIntent(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_IS_DYNAMIC_ACTIVITY, false) == true
    }
}

package com.bepinex.android.log

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.bepinex.android.BepInExLog
import com.bepinex.android.settings.AppSettings

/**
 * Attaches the in-game floating log overlay, mirroring PVZRH's UnityPlayerHooks.
 *
 * PVZRH shows the overlay from a Pine hook on the UnityPlayer constructor; this
 * port uses the Instrumentation-based runtime instead, so it is triggered once
 * from [com.bepinex.android.hooks.GameRuntime] after the game Activity resumes.
 * The user setting is honoured and the overlay is delayed a little so the game
 * window is already laid out.
 */
object FloatingLog {

    private const val ATTACH_DELAY_MS = 2000L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var attached = false

    @JvmStatic
    fun maybeAttach(activity: Activity, packageName: String) {
        if (attached) return
        attached = true
        handler.postDelayed({
            try {
                if (!AppSettings.isFloatingLogInGameEnabled(activity)) {
                    BepInExLog.i("Floating log disabled; overlay not attached")
                    return@postDelayed
                }
                GameLogOverlay.show(activity, packageName)
                BepInExLog.i("Floating log overlay attached to ${activity.javaClass.name}")
            } catch (t: Throwable) {
                BepInExLog.e("Failed to attach floating log overlay", t)
            }
        }, ATTACH_DELAY_MS)
    }
}

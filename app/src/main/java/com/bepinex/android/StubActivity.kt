package com.bepinex.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Declared stub activity used to launch dynamically loaded game activities.
 *
 * When the game is launched, [BootstrapActivity] starts this activity with a
 * wrapped intent. [com.bepinex.android.hooks.GameRuntime]'s instrumentation hook
 * replaces the instance with the real game launcher activity, so [onCreate]
 * normally never runs.
 *
 * If the activity is ever created for real (e.g. restored from recents without
 * the wrapping extras), fall back to the launcher UI.
 */
open class StubActivity : Activity() {
    override fun onCreate(bundle: Bundle?) {
        super.onCreate(null)
        startActivity(Intent(this, MainActivity::class.java))
    }
}

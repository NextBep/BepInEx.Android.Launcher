package com.bepinex.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.settings.AppSettings

/**
 * Transparent activity that handles desktop shortcut intents.
 * Selects the modpack, applies it, and launches the game.
 */
class ShortcutLauncherActivity : Activity() {

    companion object {
        const val EXTRA_SHORTCUT_PACKAGE = "shortcut_package"
        const val EXTRA_SHORTCUT_MODPACK = "shortcut_modpack"

        fun createIntent(
            context: Context,
            packageName: String,
            modpackName: String
        ): Intent {
            return Intent(context, ShortcutLauncherActivity::class.java).apply {
                putExtra(EXTRA_SHORTCUT_PACKAGE, packageName)
                putExtra(EXTRA_SHORTCUT_MODPACK, modpackName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent?.getStringExtra(EXTRA_SHORTCUT_PACKAGE)
        val modpackName = intent?.getStringExtra(EXTRA_SHORTCUT_MODPACK)

        if (packageName.isNullOrEmpty() || modpackName.isNullOrEmpty()) {
            finish()
            return
        }

        val modpackManager = ModpackManager()
        val previous = AppSettings.getActiveModpack(this, packageName)

        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    modpackManager.switchRuntime(packageName, previous, modpackName)
                }
                AppSettings.setActiveModpack(this, packageName, modpackName)
            } catch (error: Exception) {
                BepInExLog.e("Shortcut modpack switch failed", error)
            }
            runOnUiThread {
                val launchIntent = Intent(this, BootstrapActivity::class.java).apply {
                    putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, packageName)
                    putExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACK, modpackName)
                }
                startActivity(launchIntent)
                finish()
            }
        }.start()
    }
}

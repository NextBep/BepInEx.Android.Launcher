package com.bepinex.android.fusion

import android.content.Context
import com.bepinex.android.BepInExLog
import java.io.File

/**
 * Writes [FusionConfig] to disk so native libmain.so can read it.
 *
 * The config is written to:
 *   {filesDir}/bootstrap/active.cfg
 *
 * This path is resolved by libmain's load() → resolve_staged_config_path()
 * and passed to fusion_stage_from_config_path().
 */
object FusionConfigStore {

    private const val CONFIG_DIR = "bootstrap"
    private const val CONFIG_FILE = "active.cfg"

    /**
     * Write the fusion config to the staged location.
     *
     * @return The written config file (for logging/debugging)
     */
    fun write(context: Context, config: FusionConfig): File {
        val dir = File(context.filesDir, CONFIG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Failed to create config dir: ${dir.absolutePath}")
        }

        val file = File(dir, CONFIG_FILE)
        val content = config.toConfigFile()

        file.writeText(content)

        BepInExLog.i("FusionConfigStore: written to ${file.absolutePath}")
        BepInExLog.i("  content: ${content.replace("\n", " | ")}")

        return file
    }

    /**
     * Get the expected config file path (for checking if it exists).
     */
    fun getConfigFile(context: Context): File =
        File(File(context.filesDir, CONFIG_DIR), CONFIG_FILE)
}

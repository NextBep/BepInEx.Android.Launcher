package com.bepinex.android.tools

import android.content.Context
import android.content.res.Configuration
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import java.util.Locale

/**
 * Localizes the loader status messages that BepInEx emits through
 * [JniBridge.SetLoadingText] (e.g. "Running Preloader...", "Running Chainloader...").
 *
 * The managed runtime sends stable English strings; the launcher maps them to the
 * user-selected language so the loading overlay follows the app language instead of
 * always showing English. Unknown messages are returned unchanged.
 */
object LoaderText {

    private const val PLUGIN_PREFIX = "Loading "

    @JvmStatic
    fun localize(base: Context, message: String?): String? {
        if (message.isNullOrEmpty()) return message

        val context = localizedContext(base)
        return when {
            message == "Running Preloader..." -> context.getString(R.string.loader_preloader)
            message == "Initialized Chainloader..." -> context.getString(R.string.loader_initialized_chainloader)
            message == "Running Chainloader..." -> context.getString(R.string.loader_running_chainloader)
            message == "Downloading Unity assemblies..." ->
                context.getString(R.string.loader_downloading_unity_assemblies)
            message == "Running Cpp2IL..." -> context.getString(R.string.loader_running_cpp2il)
            message == "Generating IL2CPP Interop files..." -> context.getString(R.string.loader_generating_interop)
            message.startsWith(PLUGIN_PREFIX) ->
                context.getString(R.string.loader_loading_plugin, message.removePrefix(PLUGIN_PREFIX))
            else -> message
        }
    }

    /** A context whose resources use the language picked in the launcher settings. */
    private fun localizedContext(base: Context): Context {
        val locale: Locale = when (AppSettings.getLanguage(base)) {
            AppSettings.Language.ENGLISH -> Locale.forLanguageTag("en")
            AppSettings.Language.CHINESE -> Locale.forLanguageTag("zh-CN")
            AppSettings.Language.CHINESE_TW -> Locale.forLanguageTag("zh-TW")
            AppSettings.Language.SYSTEM -> return base
        }
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }
}

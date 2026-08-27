package com.bepinex.android.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application settings persisted via SharedPreferences.
 *
 * Covers: theme mode, language preference, and settings actions.
 */
object AppSettings {

    private const val PREFS_NAME = "bepinex_launcher_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_FLOATING_LOG_IN_GAME = "floating_log_in_game"
    private const val KEY_ACTIVE_MODPACK_PREFIX = "active_modpack_"

    enum class ThemeMode {
        SYSTEM, DARK, LIGHT;

        companion object {
            fun fromString(value: String?): ThemeMode = when (value) {
                "dark" -> DARK
                "light" -> LIGHT
                else -> SYSTEM  // default: follow system (Material You)
            }
        }
    }

    enum class Language(val key: String) {
        SYSTEM("system"),
        ENGLISH("en"),
        CHINESE("zh");

        companion object {
            fun fromKey(value: String?): Language = when (value) {
                "en" -> ENGLISH
                "zh" -> CHINESE
                else -> SYSTEM
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Theme

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromString(prefs(context).getString(KEY_THEME_MODE, null))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name.lowercase()).apply()
        applyDayNight(mode)
    }

    fun applyDayNight(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // Language

    fun getLanguage(context: Context): Language =
        Language.fromKey(prefs(context).getString(KEY_LANGUAGE, null))

    fun setLanguage(context: Context, language: Language) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.key).apply()
    }

    // Floating Log in Game

    fun isFloatingLogInGameEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_LOG_IN_GAME, false)

    fun setFloatingLogInGameEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_LOG_IN_GAME, enabled).apply()
    }

    fun getActiveModpack(context: Context, packageName: String): String? =
        prefs(context).getString(KEY_ACTIVE_MODPACK_PREFIX + packageName, null)?.ifEmpty { null }

    fun setActiveModpack(context: Context, packageName: String, modpackName: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_MODPACK_PREFIX + packageName, modpackName ?: "").apply()
    }

    // Initialize on startup

    fun initialize(context: Context) {
        applyDayNight(getThemeMode(context))
        // Language is handled via attachBaseContext in MainActivity  -- no need to apply here
    }
}

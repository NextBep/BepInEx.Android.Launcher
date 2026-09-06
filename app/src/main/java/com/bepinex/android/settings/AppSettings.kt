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
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_ANIMATION_DISABLED = "animation_disabled"
    private const val KEY_BLOCK_UNITY_KILL_PREFIX = "block_unity_kill_"
    private const val KEY_ACTIVE_MODPACK_PREFIX = "active_modpack_"
    private const val KEY_USE_UNSTRIPPED_LIBUNITY_PREFIX = "use_unstripped_libunity_"

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

    fun isDynamicColorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, true)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun isAnimationDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANIMATION_DISABLED, false)

    fun setAnimationDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANIMATION_DISABLED, disabled).apply()
    }

    /** Whether startup calls to UnityPlayer.kill() should be blocked for a game. */
    fun isUnityKillBlockEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_UNITY_KILL_PREFIX + packageName, false)

    fun setUnityKillBlockEnabled(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_BLOCK_UNITY_KILL_PREFIX + packageName, enabled)
            .apply()
    }

    /** Whether to download and use unstripped libunity.so for a game. */
    fun isUseUnstrippedLibUnity(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_USE_UNSTRIPPED_LIBUNITY_PREFIX + packageName, true)

    fun setUseUnstrippedLibUnity(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_USE_UNSTRIPPED_LIBUNITY_PREFIX + packageName, enabled)
            .apply()
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

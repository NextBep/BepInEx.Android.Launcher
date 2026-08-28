package com.bepinex.android.ui.navigation

/**
 * Navigation route constants for Compose Navigation.
 */
object NavRoutes {
    const val GAMES = "games"
    const val MODPACKS = "modpacks/{packageName}"
    const val MODPACK_DETAIL = "modpack_detail/{packageName}/{modpackName}"
    const val SETTINGS = "settings/{packageName}"
    const val ABOUT = "about"
    const val LOG_VIEWER = "log_viewer/{packageName}/{modpackName}"
    const val CONFIG_EDITOR = "config_editor/{filePath}"

    fun modpacks(packageName: String) = "modpacks/$packageName"
    fun modpackDetail(packageName: String, modpackName: String) =
        "modpack_detail/$packageName/$modpackName"
    fun settings(packageName: String) = "settings/$packageName"
    fun logViewer(packageName: String, modpackName: String) = "log_viewer/$packageName/$modpackName"
    fun configEditor(filePath: String) = "config_editor/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
}

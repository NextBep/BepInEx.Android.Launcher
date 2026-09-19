package com.bepinex.android.ui.navigation

/**
 * Navigation route constants for Compose Navigation.
 */
object NavRoutes {
    const val MAIN = "main"
    const val MODPACK_DETAIL = "modpack_detail/{packageName}/{modpackName}"
    const val ABOUT = "about"
    const val CREDITS = "credits"
    const val LOG_VIEWER = "log_viewer/{packageName}/{modpackName}"
    const val LOG_VIEWER_SETTINGS = "log_viewer_settings"
    const val CONFIG_EDITOR = "config_editor/{filePath}"
    const val VIEWER_SETTINGS = "viewer_settings"
    const val MOD_FILE_BROWSER = "mod_file_browser/{packageName}/{modpackName}"
    const val GAME_SETTINGS = "game_settings/{packageName}/{gameLabel}"

    fun modpackDetail(packageName: String, modpackName: String) =
        "modpack_detail/$packageName/$modpackName"
    fun gameSettings(packageName: String, gameLabel: String) =
        "game_settings/$packageName/${java.net.URLEncoder.encode(gameLabel, "UTF-8")}"
    fun logViewer(packageName: String, modpackName: String) = "log_viewer/$packageName/$modpackName"
    fun configEditor(filePath: String) = "config_editor/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
    fun modFileBrowser(packageName: String, modpackName: String) =
        "mod_file_browser/$packageName/$modpackName"
}

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

    fun modpacks(packageName: String) = "modpacks/$packageName"
    fun modpackDetail(packageName: String, modpackName: String) =
        "modpack_detail/$packageName/$modpackName"
    fun settings(packageName: String) = "settings/$packageName"
}

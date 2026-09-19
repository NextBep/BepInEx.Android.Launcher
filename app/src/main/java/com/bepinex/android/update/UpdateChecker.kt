package com.bepinex.android.update

import android.content.Context
import com.bepinex.android.BepInExLog
import com.bepinex.android.settings.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    /** Primary source: info.json at the BepInEx_Android launcher repo. */
    private const val INFO_URL_GITHUB =
        "https://raw.githubusercontent.com/NextBep/BepInEx.Android.Launcher/refs/heads/main/info.json"

    /** Mirror for regions where raw.githubusercontent.com is unreliable. */
    private const val INFO_URL_GH_PROXY =
        "https://v6.gh-proxy.org/https://github.com/NextBep/BepInEx.Android.Launcher/raw/refs/heads/main/info.json"

    data class UpdateInfo(
        val version: String,
        val allowStart: Boolean,
        val announcementDate: String,
        val announcementZh: String,
        val announcementEn: String,
        val urlApk: String
    )

    /** Parse [text](url) markdown links into pairs */
    fun parseLinks(text: String): List<Pair<String, String>> {
        val regex = Regex("""\[([^\]]+)]\s*\(([^)]+)\)""")
        return regex.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    private fun isChinese(context: Context): Boolean {
        return when (AppSettings.getLanguage(context)) {
            AppSettings.Language.CHINESE, AppSettings.Language.CHINESE_TW -> true
            AppSettings.Language.SYSTEM ->
                context.resources.configuration.locales[0]?.language?.equals("zh", ignoreCase = true) == true
            else -> false
        }
    }

    private fun infoJsonUrl(context: Context): String =
        if (isChinese(context)) INFO_URL_GH_PROXY else INFO_URL_GITHUB

    fun fetchInfo(context: Context): UpdateInfo? {
        return try {
            val url = URL(infoJsonUrl(context))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "BepInEx-Android-Launcher")
            if (conn.responseCode !in 200..299) {
                BepInExLog.w("info.json fetch failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)

            val version = json.optString("version", "")
            val allowStart = json.optString("allowStart", "true") == "true"

            val announcement = json.optJSONObject("announcement") ?: JSONObject()
            val announcementDate = announcement.optString("Date", "")
            val announcementZh = announcement.optString("SChinese", "")
            val announcementEn = announcement.optString("English", "")

            val urlsApk = json.optJSONObject("urlApk") ?: JSONObject()
            val apkPrefix = if (isChinese(context)) "gh-proxy" else "github"
            val urlApk = urlsApk.optString(apkPrefix, urlsApk.optString("github", ""))

            BepInExLog.i("info.json fetched: version=$version, allowStart=$allowStart")

            UpdateInfo(
                version = version,
                allowStart = allowStart,
                announcementDate = announcementDate,
                announcementZh = announcementZh,
                announcementEn = announcementEn,
                urlApk = urlApk
            )
        } catch (e: Exception) {
            BepInExLog.e("Failed to fetch info.json", e)
            null
        }
    }

    fun preferProxyMirrors(context: Context): Boolean = isChinese(context)

    /** Announcement body in the user's language (falls back to English). */
    fun announcementMessage(info: UpdateInfo, context: Context): String =
        if (isChinese(context) && info.announcementZh.isNotEmpty()) info.announcementZh
        else info.announcementEn

    /**
     * Compare current version with remote version, ignoring -ci.XXX suffix.
     */
    fun hasUpdate(currentVersion: String, remoteVersion: String): Boolean {
        val currentBase = currentVersion.replace(Regex("-ci\\.\\d+$"), "")
        val remoteBase = remoteVersion.replace(Regex("-ci\\.\\d+$"), "")
        return remoteBase > currentBase
    }

    fun shouldBlockStart(remoteVersion: String, currentVersion: String): Boolean {
        val currentBase = currentVersion.replace(Regex("-ci\\.\\d+$"), "")
        return currentBase > remoteVersion
    }
}

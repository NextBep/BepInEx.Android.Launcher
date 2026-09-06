package com.bepinex.android.update

import android.content.Context
import com.bepinex.android.BepInExLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/NextBep/BepInEx.Android.Launcher/releases/latest"

    data class UpdateInfo(
        val version: String,
        val tagName: String,
        val body: String,
        val htmlUrl: String,
        val publishedAt: String
    )

    fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val url = URL(RELEASES_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "BepInEx-Android-Launcher")

            if (conn.responseCode != 200) {
                BepInExLog.w("GitHub releases fetch failed: HTTP ${conn.responseCode}")
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val version = tagName.removePrefix("v")
            val releaseBody = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "")
            val publishedAt = json.optString("published_at", "")

            BepInExLog.i("Latest release: $tagName")
            UpdateInfo(
                version = version,
                tagName = tagName,
                body = releaseBody,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            BepInExLog.e("Failed to fetch GitHub releases", e)
            null
        }
    }

    /**
     * Compare current version with remote version.
     * Strips -ci.XXX suffix for CI builds.
     */
    fun hasUpdate(currentVersion: String, remoteVersion: String): Boolean {
        val currentBase = currentVersion.replace(Regex("-ci\\.\\d+$"), "")
        return currentBase != remoteVersion
    }
}

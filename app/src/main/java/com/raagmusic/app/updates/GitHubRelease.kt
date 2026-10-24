package com.raagmusic.app.updates

import org.json.JSONObject
import java.net.URI

internal const val RELEASES_URL = "https://github.com/lxexe/Raag-Music/releases/latest"
internal const val RELEASE_API = "https://api.github.com/repos/lxexe/Raag-Music/releases/latest"
internal const val DAY_MS = 24 * 60 * 60 * 1000L

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class GitHubRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?) {
    fun isNewerThan(installed: String): Boolean {
        val current = AppVersion.parse(installed) ?: return false
        return (AppVersion.parse(version) ?: return false) > current
    }
}

internal fun trustedReleaseUrl(value: String, download: Boolean = false): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.rawQuery == null && uri.rawFragment == null &&
        uri.path.startsWith(if (download) "/lxexe/Raag-Music/releases/download/" else "/lxexe/Raag-Music/releases/") &&
        (!download || uri.path.endsWith(".apk")) && !uri.path.contains("/../")
}.getOrDefault(false)

/** Release notes are displayed as text, never interpreted as HTML or executable content. */
internal fun parseRelease(json: String): GitHubRelease? {
    val root = JSONObject(json)
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
    val tag = root.optString("tag_name")
    if (AppVersion.parse(tag) == null) return null
    val version = tag.removePrefix("v")
    val page = root.optString("html_url")
    require(trustedReleaseUrl(page)) { "Invalid release page" }
    val assets = root.optJSONArray("assets")
    var apk: String? = null
    for (name in listOf("Raag-Music-$version.apk", "Raag-Music-latest.apk")) {
        for (index in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.optJSONObject(index) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name") == name && asset.optString("state") == "uploaded" && trustedReleaseUrl(url, true)) {
                apk = url
                break
            }
        }
        if (apk != null) break
    }
    val notes = if (root.isNull("body")) "" else root.optString("body").take(24_000).replace(Regex("(?s)<!--.*?-->"), "").trim()
    return GitHubRelease(version, notes, page, apk)
}

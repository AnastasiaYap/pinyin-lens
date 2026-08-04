package io.tr8.pinyinlens.update

import io.tr8.pinyinlens.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer release on GitHub.
 *
 * Uses the unauthenticated releases API, which allows 60 requests an hour per
 * IP — far more than a launch-time check needs.
 */
object UpdateChecker {

    data class Release(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    private const val TIMEOUT_MS = 15_000

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub rejects requests without one.
            setRequestProperty("User-Agent", "PinyinLens/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                // Prefer the release build; a debug APK carries a different
                // signature and could not install over it anyway.
                if (name.endsWith(".apk", ignoreCase = true) &&
                    !name.contains("debug", ignoreCase = true)
                ) {
                    apkUrl = asset.optString("browser_download_url")
                    size = asset.optLong("size")
                    break
                }
            }
            if (apkUrl.isNullOrEmpty()) return@withContext null

            Release(
                versionName = json.optString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apkUrl,
                sizeBytes = size,
            )
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Numeric-segment comparison, so 0.10.0 correctly beats 0.9.0 — which a
     * string comparison would get backwards.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = segments(candidate)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun segments(version: String): List<Int> =
        version.trim().removePrefix("v")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

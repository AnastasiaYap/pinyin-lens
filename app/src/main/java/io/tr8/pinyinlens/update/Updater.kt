package io.tr8.pinyinlens.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * There is no silent path here. Only a device-owner or system app may install
 * packages without confirmation; a sideloaded app can at most place the file in
 * front of the installer, and the user taps through. The install additionally
 * fails unless the download is signed with the same key as the installed app —
 * which is the property that stops a substituted APK taking over the package.
 */
object Updater {

    private const val TIMEOUT_MS = 20_000

    /** True if the user has allowed this app to request installs. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Streams the APK into cache. [onProgress] receives 0..100, or -1 when the
     * server does not report a length.
     */
    suspend fun download(
        context: Context,
        release: UpdateChecker.Release,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "PinyinLens-${release.versionName}.apk")

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PinyinLens")
        }

        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
            target.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            target.delete()
            null
        } finally {
            connection.disconnect()
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        runCatching { context.startActivity(intent) }
    }
}

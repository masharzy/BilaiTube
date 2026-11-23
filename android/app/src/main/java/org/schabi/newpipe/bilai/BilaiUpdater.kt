package org.schabi.newpipe.bilai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** FileProvider of its own so the update APK can be handed to the system installer. */
class BilaiUpdateProvider : FileProvider()

/**
 * Checks GitHub Releases of this repository for a newer version, downloads the APK that matches
 * the phone's CPU and opens Android's installer. Android always asks the user to confirm an
 * install from an app; that one tap cannot be skipped.
 */
internal object BilaiUpdater {
    const val REPO = "masharzy/BilaiTube"

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long)

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", "BilaiTube/" + BuildConfig.VERSION_NAME)
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /**
     * The newest release if it is newer than this build, null when this build is current.
     * Fails (instead of claiming "latest") when GitHub couldn't be asked — e.g. the API's
     * 60-requests-an-hour limit, which mobile networks sharing one IP hit quickly.
     */
    suspend fun check(): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching { fromApi() }.recoverCatching { fromWebsite() }
    }

    private fun fromApi(): Release? {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest")
        check(connection.responseCode in 200..299) { "GitHub API HTTP ${connection.responseCode}" }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val version = json.optString("tag_name").removePrefix("v")
        check(version.isNotBlank()) { "No version" }
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.optJSONArray("assets") ?: error("No files")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk") }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> apks.any { it.optString("name").contains(abi) } }
        val asset = apks.firstOrNull { abi != null && it.optString("name").contains(abi) }
            ?: apks.firstOrNull { it.optString("name").contains("universal") }
            ?: apks.firstOrNull() ?: error("No APK")
        return Release(version, json.optString("body").trim(), asset.optString("browser_download_url"),
            asset.optString("name"), asset.optLong("size"))
    }

    /** No API limit: github.com/…/releases/latest redirects to the newest tag. */
    private fun fromWebsite(): Release? {
        val connection = open("https://github.com/$REPO/releases/latest").apply { instanceFollowRedirects = false }
        val location = connection.getHeaderField("Location") ?: error("GitHub HTTP ${connection.responseCode}")
        val version = location.substringAfterLast("/tag/", "").removePrefix("v")
        check(version.isNotBlank()) { "No version" }
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") } ?: "arm64-v8a"
        val name = "BilaiTube_$version-$abi-release.apk"
        return Release(version, "", "https://github.com/$REPO/releases/download/v$version/$name", name, -1L)
    }

    /** Downloads the APK into the cache, reporting progress 0..1. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = cached(context, release)
                // Already downloaded (e.g. before granting the install permission): don't fetch again.
                if (target.exists() && (release.size <= 0 || target.length() == release.size)) return@runCatching target
                target.parentFile?.listFiles()?.forEach { if (it != target) it.delete() }
                val part = File(target.path + ".part")
                var url = release.apkUrl
                var connection = open(url)
                // GitHub answers with a redirect to its download host.
                repeat(5) {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        url = connection.getHeaderField("Location") ?: return@repeat
                        connection.disconnect()
                        connection = open(url)
                    }
                }
                if (connection.responseCode !in 200..299) return@runCatching null
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) withContext(Dispatchers.Main) { progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
                if (total > 0 && part.length() != total) { part.delete(); return@runCatching null }
                if (!part.renameTo(target)) return@runCatching null
                target
            }.getOrNull()
        }

    fun cached(context: Context, release: Release): File =
        File(File(context.cacheDir, "updates").apply { mkdirs() }, "BilaiTube-${release.version}.apk")

    fun isDownloaded(context: Context, release: Release): Boolean = cached(context, release).let {
        it.exists() && (release.size <= 0 || it.length() == release.size)
    }

    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openReleasePage(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$REPO/releases/latest"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens the system installer. Returns false when the user first has to allow installs from BilaiTube. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

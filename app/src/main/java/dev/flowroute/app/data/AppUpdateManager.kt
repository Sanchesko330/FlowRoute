package dev.flowroute.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.flowroute.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val pageUrl: String,
)

enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY }

data class AppUpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: AppRelease? = null,
    val error: String = "",
    val progress: DownloadProgress = DownloadProgress(),
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String = "",
    @SerialName("html_url") val pageUrl: String,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

class AppUpdateManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases/latest").apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val release = connection.readJson<GitHubRelease>(json)
        val version = release.tagName.removePrefix("v").removePrefix("V")
        if (!UpdateVersions.isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: error("В релизе $version нет APK")
        require(apk.downloadUrl.startsWith("https://")) { "Небезопасный адрес загрузки" }
        AppRelease(version, release.body, apk.downloadUrl, release.pageUrl)
    }

    suspend fun download(
        release: AppRelease,
        preferredProxy: java.net.Proxy? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "FlowRoute-${release.version}.apk")
        val partial = File(directory, "download.part")
        val attempts = listOfNotNull(
            preferredProxy?.let { it to "через VPN" },
            null to "напрямую",
        )
        var networkError: java.io.IOException? = null
        for ((proxy, transport) in attempts) {
            try {
                partial.delete()
                ApkHttpDownloader.download(release.downloadUrl, partial, proxy, transport, onProgress)
                networkError = null
                break
            } catch (error: java.io.IOException) {
                networkError = error
                partial.delete()
            }
        }
        networkError?.let { throw java.io.IOException("Не удалось скачать APK ни через VPN, ни напрямую: ${it.message}", it) }
        val info = context.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
            ?: error("Загружен повреждённый APK")
        require(info.packageName == context.packageName) { "APK принадлежит другому приложению" }
        val downloadedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) context.packageInfo().longVersionCode else @Suppress("DEPRECATION") context.packageInfo().versionCode.toLong()
        require(downloadedCode > currentCode) { "Версия APK не новее установленной" }
        check(partial.renameTo(target)) { "Не удалось сохранить APK" }
        target
    }

    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(install)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "FlowRoute-Android/${BuildConfig.VERSION_NAME}")
    }

    private inline fun <reified T> HttpURLConnection.readJson(json: Json): T {
        try {
            if (responseCode !in 200..299) {
                val message = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("GitHub: HTTP $responseCode${if (message.isBlank()) "" else " — $message"}")
            }
            return json.decodeFromString(inputStream.bufferedReader().use { it.readText() })
        } finally {
            disconnect()
        }
    }

    private fun Context.packageInfo(): PackageInfo = packageManager.getPackageInfo(packageName, 0)
}

object UpdateVersions {
    fun isNewer(candidate: String, current: String): Boolean {
        val left = candidate.split('.', '-', '_')
        val right = current.split('.', '-', '_')
        val count = maxOf(left.size, right.size)
        repeat(count) { index ->
            val a = left.getOrNull(index)?.toIntOrNull() ?: 0
            val b = right.getOrNull(index)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }
}

package dev.flowroute.app.data

import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

data class DownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val transport: String = "",
) {
    val fraction: Float? get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

object ApkHttpDownloader {
    fun download(
        url: String,
        target: File,
        proxy: Proxy? = null,
        transport: String,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val connection = (if (proxy == null) URI(url).toURL().openConnection() else URI(url).toURL().openConnection(proxy)) as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "FlowRoute-Android")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} при загрузке APK")
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastReportedBytes = -1L
            var lastReportedAt = 0L
            onProgress(DownloadProgress(0, total, transport))
            connection.inputStream.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (downloaded - lastReportedBytes >= 256 * 1024 || now - lastReportedAt >= 500) {
                            onProgress(DownloadProgress(downloaded, total, transport))
                            lastReportedBytes = downloaded
                            lastReportedAt = now
                        }
                    }
                }
            }
            if (total >= 0 && downloaded != total) error("Загрузка оборвалась: $downloaded из $total байт")
            if (downloaded < 1024 * 1024) error("Сервер вернул слишком маленький APK")
            onProgress(DownloadProgress(downloaded, total, transport))
        } finally {
            connection.disconnect()
        }
    }

    fun localHttp(port: Int): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    fun localSocks(port: Int): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
}

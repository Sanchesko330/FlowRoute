package dev.flowroute.app.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RegionalDataManager {
    private const val GEOSITE_URL = "https://github.com/runetfreedom/russia-blocked-geosite/releases/latest/download/geosite-ru-only.dat"
    private const val GEOIP_URL = "https://github.com/runetfreedom/russia-blocked-geoip/releases/latest/download/geoip-ru-only.dat"
    private val downloadMutex = Mutex()

    fun assetDir(context: Context): File = File(context.filesDir, "geodata")

    fun isReady(context: Context): Boolean = listOf("geosite-ru-only.dat", "geoip-ru-only.dat").all {
        File(assetDir(context), it).length() > 100_000
    }

    suspend fun ensure(context: Context, force: Boolean = false): Long = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (!force && isReady(context)) return@withLock File(assetDir(context), "geosite-ru-only.dat").lastModified()
            val directory = assetDir(context).apply { mkdirs() }
            coroutineScope {
                val geosite = async { download(GEOSITE_URL, File(directory, "geosite-ru-only.dat")) }
                val geoip = async { download(GEOIP_URL, File(directory, "geoip-ru-only.dat")) }
                geosite.await(); geoip.await()
            }
            System.currentTimeMillis()
        }
    }

    private fun download(url: String, target: File) {
        val temporary = File(target.parentFile, "${target.name}.download")
        temporary.delete()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "FlowRoute/0.5")
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> temporary.outputStream().buffered().use { output -> input.copyTo(output) } }
            if (temporary.length() < 100_000) error("Список ${target.name} повреждён")
            try {
                java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }
}

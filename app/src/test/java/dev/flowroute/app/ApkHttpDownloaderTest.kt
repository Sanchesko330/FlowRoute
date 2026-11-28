package dev.flowroute.app

import dev.flowroute.app.data.ApkHttpDownloader
import dev.flowroute.app.data.DownloadProgress
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ApkHttpDownloaderTest {
    @Test
    fun downloadsWholeFileAndReportsByteProgress() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (input.readLine()?.isNotEmpty() == true) Unit
                socket.getOutputStream().buffered().use { output ->
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(payload)
                }
            }
        }
        val directory = Files.createTempDirectory("flowroute-update-test").toFile()
        val target = directory.resolve("update.apk")
        val reports = mutableListOf<DownloadProgress>()
        try {
            ApkHttpDownloader.download(
                "http://127.0.0.1:${server.localPort}/update.apk",
                target,
                transport = "тест",
                onProgress = reports::add,
            )
            worker.join(5_000)
            assertArrayEquals(payload, target.readBytes())
            assertEquals(payload.size.toLong(), reports.last().downloadedBytes)
            assertEquals(payload.size.toLong(), reports.last().totalBytes)
            assertEquals(1f, reports.last().fraction!!)
        } finally {
            server.close()
            target.delete()
            directory.delete()
        }
    }
}

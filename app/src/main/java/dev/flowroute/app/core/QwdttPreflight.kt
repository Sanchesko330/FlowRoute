package dev.flowroute.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object QwdttPreflight {
    data class PreflightResult(val dnsArgument: String, val detail: String)

    private val providers = listOf(
        Triple("yandex", "Яндекс", listOf("77.88.8.8", "77.88.8.1")),
        Triple("cloudflare", "Cloudflare", listOf("1.1.1.1", "1.0.0.1")),
        Triple("google", "Google", listOf("8.8.8.8", "8.8.4.4")),
    )

    suspend fun run(context: Context, log: (String) -> Unit): PreflightResult = withContext(Dispatchers.IO) {
        val networkDns = networkDns(context)
        for ((argument, title, servers) in providers) {
            val checks = checkServers(servers, "login.vk.ru")
            if (checks.any { it.second }) {
                log("QWDTT DNS: $title доступен (${checks.filter { it.second }.joinToString { it.first }})")
                requireResolves(servers.first { checks.first { check -> check.first == it }.second }, "vk.com")
                checkVkHttps()
                return@withContext PreflightResult(argument, "$title · VK доступен")
            }
            log("QWDTT DNS: $title недоступен (${servers.joinToString()})")
        }
        if (networkDns.isNotEmpty()) {
            val checks = checkServers(networkDns, "login.vk.ru")
            if (checks.any { it.second }) {
                val server = checks.first { it.second }.first
                requireResolves(server, "vk.com")
                checkVkHttps()
                log("QWDTT DNS: используется DNS текущей сети $server")
                return@withContext PreflightResult("custom:${networkDns.joinToString(",")}", "DNS сети · VK доступен")
            }
            log("QWDTT DNS: DNS текущей сети недоступен (${networkDns.joinToString()})")
        }
        error("Нет доступного DNS для VK; переключение на QWDTT отменено")
    }

    private suspend fun checkServers(servers: List<String>, domain: String): List<Pair<String, Boolean>> = coroutineScope {
        servers.distinct().map { server -> async { server to probeDns(server, domain, requireAnswer = false) } }.awaitAll()
    }

    private fun requireResolves(server: String, domain: String) {
        if (!probeDns(server, domain, requireAnswer = true)) error("DNS $server не смог разрешить $domain")
    }

    private fun checkVkHttps() {
        val connection = URI("https://vk.com/").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        try {
            val code = connection.responseCode
            if (code !in 200..499) error("vk.com ответил HTTP $code")
            runCatching { connection.inputStream.close() }
        } catch (error: Throwable) {
            throw IllegalStateException("vk.com недоступен: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun probeDns(server: String, domain: String, requireAnswer: Boolean): Boolean = runCatching {
        val txId = Random.nextInt(0, 0x10000)
        val query = dnsQuery(txId, domain)
        DatagramSocket().use { socket ->
            socket.soTimeout = 2000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val data = response.data
            if (response.length < 12) return@runCatching false
            val responseId = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
            val flags = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
            val answers = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
            responseId == txId && flags and 0x8000 != 0 && flags and 0x000f == 0 && (!requireAnswer || answers > 0)
        }
    }.getOrDefault(false)

    private fun dnsQuery(txId: Int, domain: String): ByteArray {
        val labels = domain.split('.')
        val packet = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        packet[0] = (txId shr 8).toByte(); packet[1] = txId.toByte()
        packet[2] = 1; packet[5] = 1
        var offset = 12
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            packet[offset++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, packet, offset, bytes.size)
            offset += bytes.size
        }
        packet[offset++] = 0; packet[offset++] = 0; packet[offset++] = 1; packet[offset++] = 0; packet[offset] = 1
        return packet
    }

    private fun networkDns(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.allNetworks.firstOrNull { item ->
            val caps = manager.getNetworkCapabilities(item)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: return emptyList()
        return manager.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter { !it.startsWith("fe80:", true) }.distinct()
    }
}

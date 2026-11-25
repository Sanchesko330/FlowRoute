package dev.flowroute.app.core

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import dev.flowroute.app.data.activeServers
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import libXray.DialerController
import libXray.LibXray
import libXray.ProcessFinder

object XrayGateway {
    private val json = Json { ignoreUnknownKeys = true }

    fun registerPlatform(service: VpnService) {
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean = service.protect(fd.toInt())
        }
        LibXray.registerDialerController(controller)
        LibXray.registerListenerController(controller)
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        LibXray.registerProcessFinder(object : ProcessFinder {
            override fun findProcessByConnection(
                network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long,
            ): Long {
                if (Build.VERSION.SDK_INT < 30) return -1
                val protocol = if (network.equals("udp", true)) 17 else 6
                val uid = runCatching {
                    connectivity.getConnectionOwnerUid(
                        protocol,
                        InetSocketAddress(srcIP, srcPort.toInt()),
                        InetSocketAddress(destIP, destPort.toInt()),
                    )
                }.getOrDefault(Process.INVALID_UID)
                return uid.toLong()
            }
        }, Build.VERSION.SDK_INT.toLong())
    }

    fun prepareDns(service: VpnService, provider: dev.flowroute.app.data.DnsProvider) {
        registerPlatform(service)
        if (provider == dev.flowroute.app.data.DnsProvider.SYSTEM) {
            LibXray.resetDNS()
            return
        }
        LibXray.setDNS(object : DialerController {
            override fun protectFd(fd: Long): Boolean = service.protect(fd.toInt())
        }, provider.server)
    }

    fun resetDnsCache() {
        runCatching { LibXray.resetDNS() }
    }

    suspend fun start(config: JsonObject): Result<Unit> = invoke("runXray", mapOf("xrayJson" to config.toString()))
        .map { Unit }

    suspend fun stop() {
        invoke("stopXray", emptyMap())
        runCatching { LibXray.resetDNS() }
    }

    suspend fun parseServers(text: String): Result<List<JsonObject>> {
        val decoded = decodeSubscription(text).getOrElse { return Result.failure(it) }
        return invoke("convertShareLinksToXrayJson", mapOf("text" to decoded)).mapCatching { response ->
            response.getValue("data").jsonObject.getValue("outbounds").jsonArray.map { it.jsonObject }
        }
    }

    suspend fun ping(outbounds: List<JsonObject>): Result<List<Int>> {
        val configs = outbounds.map { mapOf("xrayJson" to JsonObject(mapOf("outbounds" to kotlinx.serialization.json.JsonArray(listOf(it)))).toString()) }
        return invoke("pingBatch", mapOf("configs" to configs, "timeout" to 5, "url" to "https://cp.cloudflare.com/"))
            .mapCatching { response ->
                response.getValue("data").jsonObject.getValue("results").jsonArray.map {
                    dev.flowroute.app.data.FailoverPolicy.normalizePingDelay(it.jsonObject.getValue("delay").jsonPrimitive.int)
                }
            }
    }

    suspend fun queryTraffic(): Result<Map<String, dev.flowroute.app.data.TrafficCounter>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = java.net.URI("http://127.0.0.1:49227/debug/vars").toURL()
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            try {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val outbound = json.parseToJsonElement(text).jsonObject["stats"]?.jsonObject
                    ?.get("outbound")?.jsonObject ?: return@runCatching emptyMap()
                outbound.mapValues { (_, value) ->
                    val counters = value.jsonObject
                    dev.flowroute.app.data.TrafficCounter(
                        uplink = counters["uplink"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        downlink = counters["downlink"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun queryActiveLatencies(state: dev.flowroute.app.data.AppState): List<Int> = withContext(Dispatchers.IO) {
        val enabled = state.activeServers().filter { it.enabled }
        coroutineScope {
            enabled.mapIndexed { index, _ -> async {
                runCatching {
                    val started = System.nanoTime()
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.PROBE_PORT_BASE + index))
                    val connection = java.net.URI("https://cp.cloudflare.com/").toURL().openConnection(proxy) as java.net.HttpURLConnection
                    connection.connectTimeout = 7000; connection.readTimeout = 7000
                    try {
                        if (connection.responseCode !in 200..499) return@runCatching -1
                        connection.inputStream.close()
                        ((System.nanoTime() - started) / 1_000_000).toInt()
                    } finally { connection.disconnect() }
                }.getOrDefault(-1)
            } }.awaitAll()
        }
    }

    suspend fun queryExternalIps(state: dev.flowroute.app.data.AppState): Map<String, String> = withContext(Dispatchers.IO) {
        val enabled = state.activeServers().filter { it.enabled }
        coroutineScope {
            enabled.mapIndexed { index, server -> async {
                server.id to runCatching {
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.PROBE_PORT_BASE + index))
                    val connection = java.net.URI("https://api.ipify.org/").toURL().openConnection(proxy) as java.net.HttpURLConnection
                    connection.connectTimeout = 7000; connection.readTimeout = 7000
                    try {
                        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                        connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    } finally { connection.disconnect() }
                }.getOrElse { "Недоступен: ${it.message ?: "ошибка"}" }
            } }.awaitAll().toMap()
        }
    }

    private suspend fun decodeSubscription(text: String): Result<String> =
        invoke("decodeSubscriptionText", mapOf("text" to text)).mapCatching {
            it.getValue("data").jsonObject.getValue("text").jsonPrimitive.content
        }

    private suspend fun invoke(method: String, payload: Map<String, Any?>): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val request = JsonObject(mapOf(
                "apiVersion" to kotlinx.serialization.json.JsonPrimitive(3),
                "method" to kotlinx.serialization.json.JsonPrimitive(method),
                "payload" to toJsonElement(payload),
            ))
            val response = json.parseToJsonElement(LibXray.invoke(request.toString())).jsonObject
            if (!response.getValue("success").jsonPrimitive.boolean) {
                error(response["error"]?.jsonPrimitive?.content ?: "Ошибка Xray")
            }
            response
        }
    }

    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlinx.serialization.json.JsonElement -> value
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            key.toString() to toJsonElement(item)
        })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJsonElement))
        else -> error("Неподдерживаемое значение запроса")
    }
}

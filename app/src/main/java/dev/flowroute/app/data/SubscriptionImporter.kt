package dev.flowroute.app.data

import dev.flowroute.app.core.XrayGateway
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionImporter {
    suspend fun import(name: String, url: String, pastedText: String,
        subscriptionId: String = UUID.randomUUID().toString(), groupId: String = subscriptionId,
    ): Result<Pair<Subscription, List<ServerNode>>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = pastedText.ifBlank { download(url) }
            require(text.isNotBlank()) { "Пустая подписка" }
            val outbounds = XrayGateway.parseServers(text).getOrThrow()
            require(outbounds.isNotEmpty()) { "VLESS-серверы не найдены" }
            val subscription = Subscription(subscriptionId, name.ifBlank { "Подписка" }, url,
                System.currentTimeMillis(), groupId)
            subscription to serverNodesFromOutbounds(outbounds, subscriptionId, groupId, subscription.name)
        }
    }

    private fun download(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        require(connection.url.protocol == "https" || connection.url.protocol == "http") { "Нужна HTTP(S)-ссылка" }
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "FlowRoute/1.3.8")
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "Сервер подписки вернул HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}

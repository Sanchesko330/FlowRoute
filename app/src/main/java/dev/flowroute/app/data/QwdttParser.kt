package dev.flowroute.app.data

import android.net.Uri
import java.util.UUID

object QwdttParser {
    fun parse(value: String, groupId: String): QwdttConfig {
        val text = value.trim()
        return when {
            text.startsWith("qwdtt://", true) || text.startsWith("qwdtt:config", true) -> parseQuick(text, groupId)
            text.startsWith("wdtt://", true) -> parseLegacy(text, groupId)
            else -> throw IllegalArgumentException("Нужна ссылка qwdtt:// или wdtt://")
        }
    }

    private fun parseQuick(text: String, groupId: String): QwdttConfig {
        val uri = Uri.parse(text.replace(Regex("^qwdtt:config", RegexOption.IGNORE_CASE), "qwdtt://config"))
        val peer = uri.getQueryParameter("peer")?.trim().orEmpty()
        val hashes = uri.getQueryParameter("hashes")?.trim().orEmpty()
        val password = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password") ?: ""
        require(peer.isNotBlank()) { "В QWDTT-ссылке не указан peer" }
        require(hashes.isNotBlank()) { "В QWDTT-ссылке не указан хеш VK" }
        require(password.isNotBlank()) { "В QWDTT-ссылке не указан пароль" }
        return QwdttConfig(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            name = uri.getQueryParameter("name")?.trim()?.ifBlank { null } ?: "QWDTT $peer",
            peer = peer,
            hashes = hashes,
            workers = (uri.getQueryParameter("workers")?.toIntOrNull() ?: 9).coerceIn(1, 162),
            port = (uri.getQueryParameter("port")?.toIntOrNull() ?: 9000).also {
                require(it in 1..65535) { "Некорректный порт QWDTT" }
            },
            password = password,
        )
    }

    private fun parseLegacy(text: String, groupId: String): QwdttConfig {
        val parts = text.substringAfter("://").split(':')
        require(parts.size == 6) { "Некорректная legacy-ссылка QWDTT" }
        val peer = parts[0]
        val port = parts[3]
        val password = parts[4]
        val hashes = parts[5]
        require(peer.isNotBlank() && hashes.isNotBlank() && password.isNotBlank()) { "Legacy-ссылка заполнена не полностью" }
        return QwdttConfig(
            id = UUID.randomUUID().toString(), groupId = groupId, name = "QWDTT $peer",
            peer = peer, hashes = hashes, workers = 9,
            port = port.toIntOrNull()?.also { require(it in 1..65535) } ?: 9000,
            password = password,
        )
    }
}

val QwdttConfig.peerWithPort: String
    get() {
        val value = peer.trim()
        if (value.startsWith("[") && value.contains("]:")) return value
        if (value.substringAfterLast(':', "").toIntOrNull() != null && value.contains(':')) return value
        return if (value.contains(':')) "[$value]:56000" else "$value:56000"
    }

fun AppState.activeGroup(): ServerGroup? {
    val selected = selectedGroupId?.let { id -> groups.firstOrNull { it.id == id } }
    return selected ?: groups.firstOrNull { group -> servers.any { it.groupId == group.id && it.enabled } } ?: groups.firstOrNull()
}

fun AppState.activeServers(): List<ServerNode> {
    val groupId = activeGroup()?.id ?: return emptyList()
    return servers.filter { it.groupId == groupId || (it.groupId.isBlank() && it.subscriptionId == groupId) }
}

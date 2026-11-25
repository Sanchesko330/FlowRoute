package dev.flowroute.app.data

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.endpointHost(): String? {
    val settings = this["settings"]?.jsonObject ?: return null
    val flat = settings["address"]?.jsonPrimitive?.content
    if (!flat.isNullOrBlank()) return flat
    return settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("address")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

fun JsonObject.endpointPort(): Int? {
    val settings = this["settings"]?.jsonObject ?: return null
    settings["port"]?.jsonPrimitive?.content?.toIntOrNull()?.let { return it }
    return settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("port")?.jsonPrimitive?.content?.toIntOrNull()
}

fun JsonObject.endpointUserId(): String {
    val settings = this["settings"]?.jsonObject ?: return ""
    settings["id"]?.jsonPrimitive?.content?.let { return it }
    return settings["vnext"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("users")?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("id")?.jsonPrimitive?.content.orEmpty()
}

val ServerNode.connectionKey: String
    get() = listOf(outbound.endpointHost() ?: host, (outbound.endpointPort() ?: port).toString(), outbound.endpointUserId()).joinToString("|")

fun serverNodesFromOutbounds(
    outbounds: List<JsonObject>,
    subscriptionId: String,
    groupId: String,
    subscriptionName: String,
): List<ServerNode> = outbounds.mapIndexed { index, outbound ->
    val host = outbound.endpointHost() ?: "VLESS"
    val port = outbound.endpointPort() ?: 0
    ServerNode(
        id = UUID.nameUUIDFromBytes("$subscriptionId:$host:$port:${outbound.endpointUserId()}:$index".toByteArray()).toString(),
        subscriptionId = subscriptionId,
        groupId = groupId,
        name = outbound["tag"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "${subscriptionName} · ${index + 1}",
        host = host,
        port = port,
        outbound = outbound,
    )
}

fun mergeServerNodes(existing: List<ServerNode>, incoming: List<ServerNode>): List<ServerNode> {
    val updated = incoming.map { node ->
        val old = existing.firstOrNull { it.id == node.id } ?: existing.firstOrNull { it.connectionKey == node.connectionKey }
        if (old == null) node else node.copy(
            id = old.id,
            enabled = old.enabled,
            latencyMs = old.latencyMs,
            available = old.available,
            failedChecks = old.failedChecks,
            checkedAt = old.checkedAt,
            weight = old.weight,
        )
    }
    return existing + updated.filter { candidate -> existing.none { it.id == candidate.id || it.connectionKey == candidate.connectionKey } }
}

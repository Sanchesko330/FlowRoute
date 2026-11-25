package dev.flowroute.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String = "",
    val updatedAt: Long = 0L,
    val groupId: String = "",
    val autoRefresh: Boolean = true,
    val lastError: String = "",
)

@Serializable
data class ServerGroup(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val useQwdttFallback: Boolean = false,
    val qwdttOnly: Boolean = false,
)

@Serializable
data class QwdttConfig(
    val id: String,
    val groupId: String,
    val name: String,
    val peer: String,
    val hashes: String,
    val workers: Int = 9,
    val port: Int = 9000,
    val password: String,
    val latencyMs: Int? = null,
    val available: Boolean? = null,
    val checkedAt: Long = 0L,
)

@Serializable
data class ServerNode(
    val id: String,
    val subscriptionId: String = "",
    val groupId: String = "",
    val name: String,
    val host: String,
    val port: Int,
    val outbound: JsonObject,
    val enabled: Boolean = true,
    val latencyMs: Int? = null,
    val available: Boolean? = null,
    val failedChecks: Int = 0,
    val checkedAt: Long = 0L,
    val weight: Int = 1,
)

enum class BalanceStrategy(val wireName: String, val title: String) {
    ROUND_ROBIN("roundRobin", "По очереди"),
    RANDOM("random", "Случайно"),
    LEAST_PING("leastPing", "Минимальная задержка"),
    LEAST_LOAD("leastLoad", "Минимальная нагрузка"),
}

enum class RouteAction(val title: String) {
    BALANCE("Балансировать"), DIRECT("Напрямую"), BLOCK("Блокировать"), SERVER("Конкретный сервер"),
}

@Serializable
data class RouteRule(
    val id: String,
    val name: String,
    val values: List<String>,
    val isIpRule: Boolean = false,
    val action: RouteAction = RouteAction.DIRECT,
    val serverId: String? = null,
    val enabled: Boolean = true,
    val groupId: String? = null,
)

enum class AppFilterMode { EXCLUDE_SELECTED, INCLUDE_SELECTED }
enum class RoutingChannel(val title: String) { VLESS("VLESS"), QWDTT("QWDTT") }

enum class RegionRouteProfile(val title: String) {
    ALL_VPN("Всё через VPN"),
    BLOCKED_VPN("В VPN — только заблокированные"),
    RU_DIRECT("Всё через VPN, кроме РУ"),
    WHITELIST_DIRECT("Всё через VPN, кроме белого списка"),
    WHITELIST_VPN("В VPN — только белый список"),
    ALL_DIRECT("Всё напрямую"),
}

fun RegionRouteProfile.needsRegionalData() = this == RegionRouteProfile.BLOCKED_VPN || this == RegionRouteProfile.RU_DIRECT

enum class DnsProvider(val title: String, val server: String) {
    SYSTEM("DNS текущей сети", ""),
    CLOUDFLARE("Cloudflare · 1.1.1.1", "1.1.1.1:53"),
    GOOGLE("Google · 8.8.8.8", "8.8.8.8:53"),
    YANDEX("Яндекс · 77.88.8.8", "77.88.8.8:53"),
    YANDEX_SAFE("Яндекс Safe · 77.88.8.7", "77.88.8.7:53"),
    QUAD9("Quad9 · 9.9.9.9", "9.9.9.9:53"),
}

@Serializable
data class AppSettings(
    val strategy: BalanceStrategy = BalanceStrategy.ROUND_ROBIN,
    val appFilterEnabled: Boolean = false,
    val appFilterMode: AppFilterMode = AppFilterMode.EXCLUDE_SELECTED,
    val selectedApps: Set<String> = emptySet(),
    val regionProfile: RegionRouteProfile = RegionRouteProfile.ALL_VPN,
    val regionDataUpdatedAt: Long = 0L,
    val ipv6Enabled: Boolean = false,
    val autoFailover: Boolean = true,
    val stickySessions: Boolean = true,
    val subscriptionRefreshHours: Int = 6,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val qwdttRegionProfile: RegionRouteProfile = RegionRouteProfile.ALL_VPN,
    val qwdttAppFilterEnabled: Boolean = false,
    val qwdttAppFilterMode: AppFilterMode = AppFilterMode.EXCLUDE_SELECTED,
    val qwdttSelectedApps: Set<String> = emptySet(),
)

fun AppSettings.regionProfile(channel: RoutingChannel) = if (channel == RoutingChannel.QWDTT) qwdttRegionProfile else regionProfile
fun AppSettings.appFilterEnabled(channel: RoutingChannel) = if (channel == RoutingChannel.QWDTT) qwdttAppFilterEnabled else appFilterEnabled
fun AppSettings.appFilterMode(channel: RoutingChannel) = if (channel == RoutingChannel.QWDTT) qwdttAppFilterMode else appFilterMode
fun AppSettings.selectedApps(channel: RoutingChannel) = if (channel == RoutingChannel.QWDTT) qwdttSelectedApps else selectedApps

@Serializable
data class AppLogEntry(
    val id: String,
    val timestamp: Long,
    val source: String,
    val message: String,
)

@Serializable
data class HealthSample(
    val serverId: String,
    val timestamp: Long,
    val latencyMs: Int,
) {
    val available: Boolean get() = latencyMs >= 0
}

@Serializable
data class AppState(
    val subscriptions: List<Subscription> = emptyList(),
    val groups: List<ServerGroup> = emptyList(),
    val servers: List<ServerNode> = emptyList(),
    val qwdttConfigs: List<QwdttConfig> = emptyList(),
    val selectedGroupId: String? = null,
    val routes: List<RouteRule> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val errorLogs: List<AppLogEntry> = emptyList(),
    val healthSamples: List<HealthSample> = emptyList(),
)

enum class QwdttStatus { IDLE, CHECKING_DNS, CHECKING_VK, STARTING, CONNECTED, ERROR }
data class QwdttRuntimeState(
    val status: QwdttStatus = QwdttStatus.IDLE,
    val detail: String = "Ожидание",
    val configId: String = "",
)

data class TrafficCounter(val uplink: Long = 0L, val downlink: Long = 0L) {
    val total: Long get() = uplink + downlink
}

data class TrafficSample(
    val timestamp: Long,
    val totalBytes: Long,
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

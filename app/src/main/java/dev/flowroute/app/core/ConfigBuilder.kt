package dev.flowroute.app.core

import dev.flowroute.app.data.*
import kotlinx.serialization.json.*

object ConfigBuilder {
    fun build(
        state: AppState,
        tunFd: Int,
        processUids: Map<String, Int> = emptyMap(),
        assetPath: String? = null,
        qwdttPort: Int? = null,
    ): JsonObject {
        val enabled = state.activeServers().filter { it.enabled }
        val qwdttOnly = state.activeGroup()?.qwdttOnly == true
        val fallback = qwdttPort != null
        val routeProfile = if (fallback) state.settings.qwdttRegionProfile else state.settings.regionProfile
        require(enabled.isNotEmpty() || (qwdttOnly && fallback) || (!fallback && routeProfile == RegionRouteProfile.ALL_DIRECT)) {
            "Включите VLESS-сервер, настройте QWDTT или выберите режим «Всё напрямую»"
        }
        val healthy = enabled.filter { !state.settings.autoFailover || it.available != false }
        val active = healthy.ifEmpty { enabled }
        val tagged = enabled.map { server -> JsonObject(server.outbound.toMutableMap().apply { put("tag", JsonPrimitive(server.tag)) }) }
        val balancerTags = mutableMapOf<String?, String>(null to BALANCER_TAG)
        state.groups.forEach { balancerTags[it.id] = "$GROUP_BALANCER_PREFIX${it.id}" }
        val rules = buildJsonArray {
            enabled.forEach { server -> add(rule(outbound = server.tag, inbound = listOf(server.probeTag))) }
            add(rule(outbound = "direct", ip = PRIVATE_NETWORKS))
            add(rule(outbound = "dns-out", port = "53"))
            state.routes.filter { it.enabled }.forEach { item ->
                val fixedServer = item.serverId?.let { id -> enabled.firstOrNull { it.id == id } }
                if (!fallback && item.action == RouteAction.SERVER && fixedServer == null) return@forEach
                val useBalance = item.action == RouteAction.BALANCE && !fallback
                add(rule(
                    outbound = when {
                        item.action == RouteAction.DIRECT -> "direct"
                        item.action == RouteAction.BLOCK -> "block"
                        fallback -> QWDDT_TAG
                        item.action == RouteAction.SERVER -> fixedServer?.tag
                        else -> null
                    },
                    balancer = if (useBalance) balancerTags[item.groupId] ?: BALANCER_TAG else null,
                    domains = if (item.isIpRule) null else item.values,
                    ip = if (item.isIpRule) item.values else null,
                ))
            }
            when (routeProfile) {
                RegionRouteProfile.BLOCKED_VPN -> {
                    add(rule(outbound = if (fallback) QWDDT_TAG else null,
                        balancer = if (fallback) null else BALANCER_TAG,
                        domains = listOf("ext:geosite-ru-only.dat:ru-blocked") + TELEGRAM_DOMAINS))
                    add(rule(outbound = if (fallback) QWDDT_TAG else null,
                        balancer = if (fallback) null else BALANCER_TAG,
                        ip = listOf("ext:geoip-ru-only.dat:ru-blocked", "ext:geoip-ru-only.dat:re-filter")))
                }
                RegionRouteProfile.RU_DIRECT -> {
                    add(rule(outbound = "direct", domains = RUSSIAN_DOMAINS))
                    add(rule(outbound = "direct", ip = listOf("ext:geoip-ru-only.dat:ru-whitelist")))
                }
                RegionRouteProfile.WHITELIST_DIRECT -> add(rule(outbound = "direct", domains = WhitelistPreset.domains))
                RegionRouteProfile.WHITELIST_VPN -> add(rule(outbound = if (fallback) QWDDT_TAG else null,
                    balancer = if (fallback) null else BALANCER_TAG, domains = WhitelistPreset.domains))
                RegionRouteProfile.ALL_VPN, RegionRouteProfile.ALL_DIRECT -> Unit
            }
            if (state.settings.stickySessions && !fallback && routeProfile !in setOf(RegionRouteProfile.BLOCKED_VPN, RegionRouteProfile.WHITELIST_VPN, RegionRouteProfile.ALL_DIRECT)) {
                processUids.entries.sortedBy { it.key }.forEach { (packageName, uid) ->
                    val server = active[((packageName.hashCode().toLong() and Long.MAX_VALUE) % active.size).toInt()]
                    add(rule(outbound = server.tag, process = listOf(uid.toString())))
                }
            }
            if (routeProfile in setOf(RegionRouteProfile.BLOCKED_VPN, RegionRouteProfile.WHITELIST_VPN, RegionRouteProfile.ALL_DIRECT)) add(rule(outbound = "direct", network = "tcp,udp"))
            else add(rule(outbound = if (fallback) QWDDT_TAG else null,
                balancer = if (fallback) null else BALANCER_TAG, network = "tcp,udp"))
        }
        val probeInbounds = enabled.mapIndexed { index, server -> buildJsonObject {
            put("tag", server.probeTag); put("protocol", "http"); put("listen", "127.0.0.1"); put("port", PROBE_PORT_BASE + index)
            put("settings", buildJsonObject { put("allowTransparent", false) })
        } }
        val qwdttOutbound = if (qwdttPort == null) emptyList() else listOf(buildJsonObject {
            put("tag", QWDDT_TAG); put("protocol", "socks")
            put("settings", buildJsonObject { put("servers", buildJsonArray {
                add(buildJsonObject { put("address", "127.0.0.1"); put("port", qwdttPort) })
            }) })
        })
        return buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning") })
            put("env", buildJsonObject {
                put("xray.tun.fd", tunFd.toString())
                if (assetPath != null && routeProfile.needsRegionalData()) put("xray.location.asset", assetPath)
            })
            put("inbounds", JsonArray(listOf(buildJsonObject {
                put("tag", "tun-in"); put("protocol", "tun"); put("port", 0)
                put("settings", buildJsonObject { put("name", "FlowRoute"); put("mtu", 1500) })
                put("sniffing", buildJsonObject {
                    put("enabled", true); put("destOverride", JsonArray(listOf(JsonPrimitive("http"), JsonPrimitive("tls"), JsonPrimitive("quic"))))
                })
            }) + probeInbounds))
            put("outbounds", JsonArray(tagged + qwdttOutbound + listOf(
                buildJsonObject { put("tag", "direct"); put("protocol", "freedom") },
                buildJsonObject { put("tag", "block"); put("protocol", "blackhole") },
                buildJsonObject { put("tag", "dns-out"); put("protocol", "dns") },
            )))
            put("stats", buildJsonObject {})
            put("policy", buildJsonObject { put("system", buildJsonObject {
                put("statsOutboundUplink", true); put("statsOutboundDownlink", true)
            }) })
            put("metrics", buildJsonObject { put("listen", "127.0.0.1:49227") })
            put("routing", buildJsonObject {
                put("domainStrategy", "IPIfNonMatch"); put("rules", rules)
                put("balancers", JsonArray(balancerTags.map { (groupId, tag) ->
                    val members = active.filter { groupId == null || it.groupId == groupId }
                    if (members.isEmpty()) return@map null
                    buildJsonObject {
                        put("tag", tag); put("selector", JsonArray(members.map { JsonPrimitive(it.tag) })); put("fallbackTag", members.first().tag)
                        put("strategy", buildJsonObject {
                            put("type", state.settings.strategy.wireName)
                            if (state.settings.strategy == BalanceStrategy.LEAST_LOAD) put("settings", buildJsonObject {
                                put("expected", members.size)
                                put("costs", JsonArray(members.map { buildJsonObject {
                                    put("regexp", false); put("match", it.tag); put("value", it.weight.coerceIn(1, 10))
                                } }))
                            })
                        })
                    }
                }.filterNotNull()))
            })
            put("observatory", buildJsonObject {
                put("subjectSelector", JsonArray(active.map { JsonPrimitive(it.tag) }))
                put("probeURL", "https://cp.cloudflare.com/"); put("probeInterval", "1m"); put("enableConcurrency", true)
            })
        }
    }

    private fun rule(outbound: String? = null, balancer: String? = null, domains: List<String>? = null,
        ip: List<String>? = null, port: String? = null, network: String? = null,
        inbound: List<String>? = null, process: List<String>? = null) = buildJsonObject {
        put("type", "field")
        outbound?.let { put("outboundTag", it) }; balancer?.let { put("balancerTag", it) }
        domains?.let { put("domain", JsonArray(it.map(::JsonPrimitive))) }; ip?.let { put("ip", JsonArray(it.map(::JsonPrimitive))) }
        port?.let { put("port", it) }; network?.let { put("network", it) }
        inbound?.let { put("inboundTag", JsonArray(it.map(::JsonPrimitive))) }
        process?.let { put("process", JsonArray(it.map(::JsonPrimitive))) }
    }

    val ServerNode.tag: String get() = "proxy-$id"
    val ServerNode.probeTag: String get() = "probe-$id"
    const val PROBE_PORT_BASE = 49300
    const val QWDDT_TAG = "qwdtt-vk"
    private const val BALANCER_TAG = "flow-balance"
    private const val GROUP_BALANCER_PREFIX = "flow-group-"
    private val PRIVATE_NETWORKS = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10")
    private val TELEGRAM_DOMAINS = listOf(
        "domain:telegram.org", "domain:telegram.me", "domain:t.me", "domain:tdesktop.com",
        "domain:telesco.pe", "domain:telegram.dog", "domain:cdn-telegram.org",
    )
    private val RUSSIAN_DOMAINS = listOf(
        "domain:ru", "domain:рф", "domain:su", "domain:yandex.com", "domain:yandex.net", "domain:yastatic.net",
        "domain:vk.com", "domain:vk.ru", "domain:vkuseraudio.net", "domain:vkvideo.ru", "domain:mail.ru",
        "domain:ok.ru", "domain:odnoklassniki.ru", "domain:sberbank.ru", "domain:sber.ru", "domain:tinkoff.ru",
        "domain:tbank.ru", "domain:alfabank.ru", "domain:vtb.ru", "domain:gosuslugi.ru", "domain:mos.ru",
        "domain:avito.ru", "domain:wildberries.ru", "domain:wb.ru", "domain:ozon.ru", "domain:lamoda.ru",
        "domain:rutube.ru", "domain:kinopoisk.ru", "domain:ivi.ru", "domain:okko.tv", "domain:smotreshka.tv",
    )
}

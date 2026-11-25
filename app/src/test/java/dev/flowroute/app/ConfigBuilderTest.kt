package dev.flowroute.app

import dev.flowroute.app.core.ConfigBuilder
import dev.flowroute.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    private fun server(id: String, enabled: Boolean = true) = ServerNode(
        id = id,
        subscriptionId = "sub",
        groupId = "g",
        name = id,
        host = "$id.example.com",
        port = 443,
        enabled = enabled,
        outbound = buildJsonObject {
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", "$id.example.com")
                        put("port", 443)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", "00000000-0000-0000-0000-000000000000")
                                put("encryption", "none")
                            })
                        })
                    })
                })
            })
        },
    )

    private fun testState(
        servers: List<ServerNode> = emptyList(),
        routes: List<RouteRule> = emptyList(),
        settings: AppSettings = AppSettings(),
    ) = AppState(
        groups = listOf(ServerGroup("g", "Test")),
        selectedGroupId = "g",
        servers = servers,
        routes = routes,
        settings = settings,
    )

    @Test
    fun `all enabled servers join balancer and receive fd`() {
        val state = testState(servers = listOf(server("a"), server("b"), server("off", false)))
        val config = ConfigBuilder.build(state, 17)
        assertEquals("17", config.getValue("env").jsonObject.getValue("xray.tun.fd").jsonPrimitive.content)
        val balancer = config.getValue("routing").jsonObject.getValue("balancers").jsonArray.map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == "flow-balance" }
        assertEquals(listOf("proxy-a", "proxy-b"), balancer.getValue("selector").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(5, config.getValue("outbounds").jsonArray.size)
        assertEquals("127.0.0.1:49227", config.getValue("metrics").jsonObject.getValue("listen").jsonPrimitive.content)
        assertTrue(config.getValue("policy").jsonObject.getValue("system").jsonObject.getValue("statsOutboundUplink").jsonPrimitive.boolean)
        assertTrue(config.getValue("policy").jsonObject.getValue("system").jsonObject.getValue("statsOutboundDownlink").jsonPrimitive.boolean)
    }

    @Test
    fun `custom route precedes catch all`() {
        val route = RouteRule("r", "Bank", listOf("domain:bank.example"), action = RouteAction.DIRECT)
        val config = ConfigBuilder.build(testState(servers = listOf(server("a")), routes = listOf(route)), 1)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        val custom = rules.single { it.jsonObject["domain"]?.jsonArray?.contains(JsonPrimitive("domain:bank.example")) == true }.jsonObject
        assertEquals("direct", custom.getValue("outboundTag").jsonPrimitive.content)
        assertTrue(rules.last().jsonObject.containsKey("balancerTag"))
        assertEquals("tcp,udp", rules.last().jsonObject.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun `fixed server route uses selected active outbound`() {
        val route = RouteRule("r", "Work", listOf("domain:work.example"), action = RouteAction.SERVER, serverId = "b")
        val config = ConfigBuilder.build(testState(servers = listOf(server("a"), server("b")), routes = listOf(route)), 1)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray
        val custom = rules.single { it.jsonObject["domain"] != null }.jsonObject
        assertEquals("proxy-b", custom.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `failed nodes are excluded and sticky app has stable route`() {
        val state = testState(servers = listOf(server("a").copy(available = false), server("b")))
        val config = ConfigBuilder.build(state, 1, mapOf("example.app" to 10123))
        val balancer = config.getValue("routing").jsonObject.getValue("balancers").jsonArray.map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == "flow-balance" }
        assertEquals(listOf("proxy-b"), balancer.getValue("selector").jsonArray.map { it.jsonPrimitive.content })
        val sticky = config.getValue("routing").jsonObject.getValue("rules").jsonArray.single {
            it.jsonObject["process"]?.jsonArray?.contains(JsonPrimitive("10123")) == true
        }.jsonObject
        assertEquals("proxy-b", sticky.getValue("outboundTag").jsonPrimitive.content)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["inboundTag"]?.jsonArray?.contains(JsonPrimitive("probe-a")) == true && it["outboundTag"]?.jsonPrimitive?.content == "proxy-a" })
        assertEquals(2, config.getValue("inbounds").jsonArray.count { it.jsonObject["tag"]?.jsonPrimitive?.content?.startsWith("probe-") == true })
    }

    @Test
    fun `least load writes weights and diagnostic inbounds stay local`() {
        val state = testState(servers = listOf(server("a").copy(weight = 4)), settings = AppSettings(strategy = BalanceStrategy.LEAST_LOAD))
        val config = ConfigBuilder.build(state, 1)
        val strategy = config.getValue("routing").jsonObject.getValue("balancers").jsonArray.map { it.jsonObject }.single { it["tag"]?.jsonPrimitive?.content == "flow-balance" }.getValue("strategy").jsonObject
        val cost = strategy.getValue("settings").jsonObject.getValue("costs").jsonArray.single().jsonObject
        assertEquals(4, cost.getValue("value").jsonPrimitive.int)
        val probe = config.getValue("inbounds").jsonArray.last().jsonObject
        assertEquals("127.0.0.1", probe.getValue("listen").jsonPrimitive.content)
        assertEquals(ConfigBuilder.PROBE_PORT_BASE, probe.getValue("port").jsonPrimitive.int)
    }

    @Test
    fun `blocked profile proxies only regional lists and uses direct fallback`() {
        val state = testState(servers = listOf(server("a")), settings = AppSettings(regionProfile = RegionRouteProfile.BLOCKED_VPN))
        val config = ConfigBuilder.build(state, 1, assetPath = "/data/geodata")
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["domain"]?.jsonArray?.contains(JsonPrimitive("ext:geosite-ru-only.dat:ru-blocked")) == true })
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
        assertEquals("/data/geodata", config.getValue("env").jsonObject.getValue("xray.location.asset").jsonPrimitive.content)
    }

    @Test
    fun `russian direct profile precedes sticky and catch all`() {
        val state = testState(servers = listOf(server("a")), settings = AppSettings(regionProfile = RegionRouteProfile.RU_DIRECT))
        val rules = ConfigBuilder.build(state, 1, mapOf("example.app" to 10123), "/data/geodata")
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val regional = rules.indexOfFirst { it["ip"]?.jsonArray?.contains(JsonPrimitive("ext:geoip-ru-only.dat:ru-whitelist")) == true }
        val sticky = rules.indexOfFirst { it["process"] != null }
        assertTrue(regional in 0 until sticky)
        assertTrue(rules.last().containsKey("balancerTag"))
    }

    @Test
    fun `russian app preset selects only installed known packages`() {
        val selected = RussianApps.installedMatches(listOf("com.vkontakte.android", "org.example", "ru.yandex.taxi"))
        assertEquals(setOf("com.vkontakte.android", "ru.yandex.taxi"), selected)
    }

    @Test
    fun `qwdtt fallback keeps vless probes and routes traffic to socks`() {
        val config = ConfigBuilder.build(testState(servers = listOf(server("a"))), 1, qwdttPort = 49500)
        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        val qwdtt = outbounds.single { it["tag"]?.jsonPrimitive?.content == ConfigBuilder.QWDDT_TAG }
        assertEquals(49500, qwdtt.getValue("settings").jsonObject.getValue("servers").jsonArray.single().jsonObject.getValue("port").jsonPrimitive.int)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(ConfigBuilder.QWDDT_TAG, rules.last().getValue("outboundTag").jsonPrimitive.content)
        assertTrue(rules.any { it["outboundTag"]?.jsonPrimitive?.content == "proxy-a" && it["inboundTag"] != null })
    }

    @Test
    fun `qwdtt honors direct custom rules and blocked profile fallback`() {
        val directRule = RouteRule("r", "Direct", listOf("domain:example.com"), action = RouteAction.DIRECT)
        val state = testState(servers = listOf(server("a")), routes = listOf(directRule),
            settings = AppSettings(qwdttRegionProfile = RegionRouteProfile.BLOCKED_VPN))
        val rules = ConfigBuilder.build(state, 1, assetPath = "/data/geodata", qwdttPort = 49500)
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("direct", rules.single {
            it["domain"]?.jsonArray?.contains(JsonPrimitive("domain:example.com")) == true
        }.getValue("outboundTag").jsonPrimitive.content)
        assertEquals(ConfigBuilder.QWDDT_TAG, rules.single {
            it["domain"]?.jsonArray?.contains(JsonPrimitive("ext:geosite-ru-only.dat:ru-blocked")) == true
        }.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `whitelist preset contains direct domains and installed apps`() {
        assertTrue(WhitelistPreset.domains.contains("domain:gosuslugi.ru"))
        val apps = listOf("com.vkontakte.android" to "VK", "org.example" to "Other")
        assertEquals(setOf("com.vkontakte.android"), WhitelistPreset.installedMatches(apps))
    }

    @Test
    fun `qwdtt keeps russian whitelist direct`() {
        val state = testState(servers = listOf(server("a")), settings = AppSettings(qwdttRegionProfile = RegionRouteProfile.RU_DIRECT))
        val rules = ConfigBuilder.build(state, 1, assetPath = "/data/geodata", qwdttPort = 49500)
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("direct", rules.single {
            it["ip"]?.jsonArray?.contains(JsonPrimitive("ext:geoip-ru-only.dat:ru-whitelist")) == true
        }.getValue("outboundTag").jsonPrimitive.content)
        assertEquals(ConfigBuilder.QWDDT_TAG, rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `vless and qwdtt use independent regional profiles`() {
        val settings = AppSettings(regionProfile = RegionRouteProfile.RU_DIRECT, qwdttRegionProfile = RegionRouteProfile.ALL_VPN)
        val state = testState(servers = listOf(server("a")), settings = settings)
        val vlessRules = ConfigBuilder.build(state, 1, assetPath = "/data/geodata")
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(vlessRules.any { it["ip"]?.jsonArray?.contains(JsonPrimitive("ext:geoip-ru-only.dat:ru-whitelist")) == true })
        val qwdttConfig = ConfigBuilder.build(state, 1, assetPath = "/data/geodata", qwdttPort = 49500)
        val qwdttRules = qwdttConfig.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(qwdttRules.none { it["ip"]?.jsonArray?.contains(JsonPrimitive("ext:geoip-ru-only.dat:ru-whitelist")) == true })
        assertTrue(qwdttConfig.getValue("env").jsonObject["xray.location.asset"] == null)
    }

    @Test
    fun `qwdtt only works without vless and sends catch all to vk`() {
        val state = testState().copy(groups = listOf(ServerGroup("g", "Test", qwdttOnly = true, useQwdttFallback = true)))
        val config = ConfigBuilder.build(state, 1, qwdttPort = 49500)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(ConfigBuilder.QWDDT_TAG, rules.last().getValue("outboundTag").jsonPrimitive.content)
        assertTrue(config.getValue("routing").jsonObject.getValue("balancers").jsonArray.isEmpty())
        assertTrue(config.getValue("outbounds").jsonArray.any {
            it.jsonObject["tag"]?.jsonPrimitive?.content == ConfigBuilder.QWDDT_TAG
        })
    }

    @Test
    fun `all direct cannot be overridden by sticky sessions`() {
        val state = testState(servers = listOf(server("a")), settings = AppSettings(regionProfile = RegionRouteProfile.ALL_DIRECT))
        val rules = ConfigBuilder.build(state, 1, mapOf("example.app" to 10123))
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.none { it["process"] != null })
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `only whitelist uses vpn and everything else is direct`() {
        val state = testState(servers = listOf(server("a")), settings = AppSettings(regionProfile = RegionRouteProfile.WHITELIST_VPN))
        val rules = ConfigBuilder.build(state, 1, mapOf("example.app" to 10123))
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val whitelist = rules.single { it["domain"]?.jsonArray?.contains(JsonPrimitive("domain:gosuslugi.ru")) == true }
        assertEquals("flow-balance", whitelist.getValue("balancerTag").jsonPrimitive.content)
        assertTrue(rules.none { it["process"] != null })
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `all direct works without proxy servers`() {
        val state = testState(settings = AppSettings(regionProfile = RegionRouteProfile.ALL_DIRECT))
        val config = ConfigBuilder.build(state, 1)
        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("direct", rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `qwdtt handles fixed server rule when selected vless is unavailable`() {
        val fixed = RouteRule("r", "Fixed", listOf("domain:example.com"), action = RouteAction.SERVER, serverId = "missing")
        val state = testState(servers = listOf(server("a")), routes = listOf(fixed),
            settings = AppSettings(qwdttRegionProfile = RegionRouteProfile.ALL_DIRECT))
        val rules = ConfigBuilder.build(state, 1, qwdttPort = 49500)
            .getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(ConfigBuilder.QWDDT_TAG, rules.single {
            it["domain"]?.jsonArray?.contains(JsonPrimitive("domain:example.com")) == true
        }.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `dns hijack precedes custom and sticky routes`() {
        val route = RouteRule("r", "Custom", listOf("domain:example.com"), action = RouteAction.DIRECT)
        val rules = ConfigBuilder.build(testState(servers = listOf(server("a")), routes = listOf(route)), 1,
            mapOf("example.app" to 10123)).getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val dns = rules.indexOfFirst { it["port"]?.jsonPrimitive?.content == "53" }
        assertTrue(dns >= 0)
        assertTrue(dns < rules.indexOfFirst { it["domain"]?.jsonArray?.contains(JsonPrimitive("domain:example.com")) == true })
        assertTrue(dns < rules.indexOfFirst { it["process"] != null })
    }

    @Test
    fun `fixed server route does not silently move to another server`() {
        val fixed = RouteRule("r", "Fixed", listOf("domain:example.com"), action = RouteAction.SERVER, serverId = "a")
        val state = testState(servers = listOf(server("a").copy(available = false), server("b")), routes = listOf(fixed))
        val rules = ConfigBuilder.build(state, 1).getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("proxy-a", rules.single {
            it["domain"]?.jsonArray?.contains(JsonPrimitive("domain:example.com")) == true
        }.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty pool is rejected`() {
        ConfigBuilder.build(testState(), 1)
    }
}

package dev.flowroute.app

import dev.flowroute.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerImportTest {
    private fun flat(id: String, host: String = "example.com", port: Int = 443) = buildJsonObject {
        put("protocol", "vless")
        put("settings", buildJsonObject {
            put("address", host); put("port", port); put("id", id); put("encryption", "none")
        })
    }

    private fun nested(id: String) = buildJsonObject {
        put("protocol", "vless")
        put("settings", buildJsonObject {
            put("vnext", buildJsonArray { add(buildJsonObject {
                put("address", "nested.example"); put("port", 8443)
                put("users", buildJsonArray { add(buildJsonObject { put("id", id) }) })
            }) })
        })
    }

    @Test
    fun `flat and nested Xray formats expose real endpoints`() {
        assertEquals("example.com", flat("a").endpointHost())
        assertEquals(443, flat("a").endpointPort())
        assertEquals("nested.example", nested("a").endpointHost())
        assertEquals(8443, nested("a").endpointPort())
    }

    @Test
    fun `different users on same endpoint are not duplicates`() {
        val nodes = serverNodesFromOutbounds(listOf(flat("user-a"), flat("user-b")), "sub", "group", "Test")
        val merged = mergeServerNodes(emptyList(), nodes)
        assertEquals(2, merged.size)
        assertNotEquals(merged[0].connectionKey, merged[1].connectionKey)
    }

    @Test
    fun `reimport preserves user state by full connection identity`() {
        val original = serverNodesFromOutbounds(listOf(flat("user-a")), "sub", "group", "Test").single()
            .copy(enabled = false, weight = 7)
        val imported = serverNodesFromOutbounds(listOf(flat("user-a")), "new-sub", "group", "Test").single()
        val merged = mergeServerNodes(listOf(original), listOf(imported)).single()
        assertEquals(original.id, merged.id)
        assertEquals(false, merged.enabled)
        assertEquals(7, merged.weight)
    }
}

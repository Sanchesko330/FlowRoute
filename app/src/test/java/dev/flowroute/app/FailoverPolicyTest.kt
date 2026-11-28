package dev.flowroute.app

import dev.flowroute.app.data.FailoverPolicy
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverPolicyTest {
    @Test
    fun excludesServerOnlyAfterTwoConsecutiveFailures() {
        val first = FailoverPolicy.nextState(0, successful = false)
        assertEquals(1, first.first)
        assertEquals(true, first.second)
        val second = FailoverPolicy.nextState(first.first, successful = false)
        assertEquals(2, second.first)
        assertEquals(false, second.second)
    }

    @Test
    fun restoresServerAfterFirstSuccessfulCheck() {
        val recovered = FailoverPolicy.nextState(4, successful = true)
        assertEquals(0, recovered.first)
        assertEquals(true, recovered.second)
    }

    @Test
    fun checksFailuresMoreOftenWithoutHotStandby() {
        assertEquals(300_000L, FailoverPolicy.checkInterval(emptyList()))
    }

    @Test
    fun firstFailureSchedulesRetryWithoutFiveMinuteDelay() {
        val server = dev.flowroute.app.data.ServerNode(
            id = "a", groupId = "g", name = "A", host = "a.example.com", port = 443,
            outbound = JsonObject(emptyMap()), failedChecks = 1,
        )
        assertEquals(30_000L, FailoverPolicy.checkInterval(listOf(server)))
    }

    @Test
    fun xrayTimeoutIsFailureRatherThanSuccessfulPing() {
        assertEquals(-1, FailoverPolicy.normalizePingDelay(10_000))
        assertEquals(-1, FailoverPolicy.normalizePingDelay(-1))
        assertEquals(250, FailoverPolicy.normalizePingDelay(250))
    }

    @Test
    fun healthChangesDoNotRestartCoreOrDropApplicationSessions() {
        val server = dev.flowroute.app.data.ServerNode(
            id = "a", groupId = "g", name = "A", host = "a.example.com", port = 443,
            outbound = JsonObject(emptyMap()),
        )
        val state = dev.flowroute.app.data.AppState(
            groups = listOf(dev.flowroute.app.data.ServerGroup("g", "Test")),
            selectedGroupId = "g", servers = listOf(server),
        )
        val initial = FailoverPolicy.coreRouteKey(state)
        val afterHealthChecks = state.copy(servers = listOf(server.copy(
            available = false, latencyMs = -1, failedChecks = 2, checkedAt = 123456L,
        )))
        assertEquals(initial, FailoverPolicy.coreRouteKey(afterHealthChecks))
        assertEquals(initial, FailoverPolicy.coreRouteKey(state.copy(servers = listOf(server.copy(
            available = true, latencyMs = 120, failedChecks = 0, checkedAt = 223456L,
        )))))
    }

    @Test
    fun enablingOrDisablingServerStillUpdatesCore() {
        val server = dev.flowroute.app.data.ServerNode(
            id = "a", groupId = "g", name = "A", host = "a.example.com", port = 443,
            outbound = JsonObject(emptyMap()),
        )
        val state = dev.flowroute.app.data.AppState(
            groups = listOf(dev.flowroute.app.data.ServerGroup("g", "Test")),
            selectedGroupId = "g", servers = listOf(server),
        )
        val initial = FailoverPolicy.coreRouteKey(state)
        assertEquals(false, initial == FailoverPolicy.coreRouteKey(state.copy(servers = listOf(server.copy(enabled = false)))))
    }
}

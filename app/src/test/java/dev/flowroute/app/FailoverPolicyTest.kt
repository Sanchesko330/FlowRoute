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
    fun xrayTimeoutIsFailureRatherThanSuccessfulPing() {
        assertEquals(-1, FailoverPolicy.normalizePingDelay(10_000))
        assertEquals(-1, FailoverPolicy.normalizePingDelay(-1))
        assertEquals(250, FailoverPolicy.normalizePingDelay(250))
    }
}

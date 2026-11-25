package dev.flowroute.app

import dev.flowroute.app.data.HealthAnalytics
import dev.flowroute.app.data.HealthSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAnalyticsTest {
    @Test
    fun calculatesAvailabilityAndCompletedDowntime() {
        val samples = listOf(
            HealthSample("one", 0, 120),
            HealthSample("one", 60_000, -1),
            HealthSample("one", 120_000, -1),
            HealthSample("one", 240_000, 140),
        )
        val result = HealthAnalytics.summarize(samples, 300_000)
        assertEquals(4, result.checks)
        assertEquals(50, result.availabilityPercent)
        assertEquals(180_000, result.downtimeMillis)
        assertFalse(result.currentlyDown)
    }

    @Test
    fun countsOngoingDowntimeUntilNow() {
        val result = HealthAnalytics.summarize(listOf(HealthSample("one", 60_000, -1)), 360_000)
        assertEquals(0, result.availabilityPercent)
        assertEquals(300_000, result.downtimeMillis)
        assertTrue(result.currentlyDown)
    }

    @Test
    fun handlesMissingHistory() {
        val result = HealthAnalytics.summarize(emptyList(), 1000)
        assertEquals(0, result.checks)
        assertEquals(0, result.downtimeMillis)
    }
}

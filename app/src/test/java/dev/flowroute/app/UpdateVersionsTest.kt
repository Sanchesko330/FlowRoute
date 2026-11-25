package dev.flowroute.app

import dev.flowroute.app.data.UpdateVersions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionsTest {
    @Test
    fun comparesNumericVersions() {
        assertTrue(UpdateVersions.isNewer("1.4.0", "1.3.9"))
        assertTrue(UpdateVersions.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateVersions.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateVersions.isNewer("1.3.9", "1.3.9"))
        assertFalse(UpdateVersions.isNewer("1.3.8", "1.3.9"))
    }
}

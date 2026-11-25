package dev.flowroute.app

import dev.flowroute.app.data.AppState
import dev.flowroute.app.data.ConfigurationBackupCodec
import dev.flowroute.app.data.ServerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigurationBackupTest {
    @Test
    fun roundTripPreservesConfiguration() {
        val state = AppState(groups = listOf(ServerGroup(id = "group-1", name = "Основная")), selectedGroupId = "group-1")
        val restored = ConfigurationBackupCodec.decode(ConfigurationBackupCodec.encode(state))
        assertEquals(state, restored)
    }

    @Test
    fun rejectsUnrelatedJson() {
        try {
            ConfigurationBackupCodec.decode("""{"format":"another-app","version":1,"state":{}}""")
            fail("Unrelated backup must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("FlowRoute"))
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val raw = ConfigurationBackupCodec.encode(AppState()).replace("\"version\": 1", "\"version\": 99")
        try {
            ConfigurationBackupCodec.decode(raw)
            fail("Unsupported backup must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("не поддерживается"))
        }
    }
}

package dev.flowroute.app

import dev.flowroute.app.data.AppFilterMode
import dev.flowroute.app.data.RecommendedRouting
import dev.flowroute.app.data.RegionRouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedRoutingTest {
    @Test
    fun vlessUsesOnlyBlockedAppsAndResources() {
        val settings = RecommendedRouting.settings(listOf(
            "org.telegram.messenger" to "Telegram",
            "com.vkontakte.android" to "VK",
        ))
        assertEquals(RegionRouteProfile.BLOCKED_VPN, settings.regionProfile)
        assertTrue(settings.appFilterEnabled)
        assertEquals(AppFilterMode.INCLUDE_SELECTED, settings.appFilterMode)
        assertEquals(setOf("org.telegram.messenger"), settings.selectedApps)
    }

    @Test
    fun qwdttUsesEverythingExceptWhitelistApps() {
        val settings = RecommendedRouting.settings(listOf(
            "org.telegram.messenger" to "Telegram",
            "com.vkontakte.android" to "VK",
        ))
        assertEquals(RegionRouteProfile.ALL_VPN, settings.qwdttRegionProfile)
        assertTrue(settings.qwdttAppFilterEnabled)
        assertEquals(AppFilterMode.EXCLUDE_SELECTED, settings.qwdttAppFilterMode)
        assertEquals(setOf("com.vkontakte.android"), settings.qwdttSelectedApps)
    }
}

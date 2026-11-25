package dev.flowroute.app

import dev.flowroute.app.data.BlockedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedAppsTest {
    @Test
    fun selectsOnlyInstalledBrowsersAndRestrictedServices() {
        val installed = listOf(
            "com.android.chrome" to "Chrome",
            "com.google.android.youtube" to "YouTube",
            "com.instagram.android" to "Instagram",
            "com.openai.chatgpt" to "ChatGPT",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.vkontakte.android" to "VK",
            "ru.sberbankmobile" to "Сбер",
        )

        assertEquals(
            setOf(
                "com.android.chrome", "com.google.android.youtube", "com.instagram.android",
                "com.openai.chatgpt", "com.spotify.music", "com.google.android.apps.youtube.music",
            ),
            BlockedApps.installedMatches(installed),
        )
    }

    @Test
    fun ignoresUninstalledPackagesAndUnrelatedLabels() {
        assertTrue(BlockedApps.installedMatches(listOf("com.example.browser" to "Browser")) .isEmpty())
        assertTrue(BlockedApps.installedMatches(emptyList()).isEmpty())
    }
}

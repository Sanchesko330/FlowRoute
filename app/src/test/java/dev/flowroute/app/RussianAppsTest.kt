package dev.flowroute.app

import dev.flowroute.app.data.RussianApps
import org.junit.Assert.assertEquals
import org.junit.Test

class RussianAppsTest {
    @Test
    fun matchesKnownPackagesAndVariantsOnly() {
        val installed = listOf(
            "com.ozon.app",
            "ru.yandex.superapp",
            "ru.sber.sbermarket",
            "ru.mts.music",
            "com.example.foreign",
        )

        assertEquals(
            setOf("com.ozon.app", "ru.yandex.superapp", "ru.sber.sbermarket", "ru.mts.music"),
            RussianApps.installedMatches(installed),
        )
    }

    @Test
    fun matchesRussianLabelsWithUnknownPackages() {
        val installed = listOf(
            "vendor.bank" to "Т-Банк",
            "vendor.video" to "Кинопоиск",
            "vendor.maps" to "Яндекс Карты",
            "com.example.browser" to "Browser",
        )

        assertEquals(
            setOf("vendor.bank", "vendor.video", "vendor.maps"),
            RussianApps.installedMatches(installed),
        )
    }
}

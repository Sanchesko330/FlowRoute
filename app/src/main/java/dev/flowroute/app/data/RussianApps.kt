package dev.flowroute.app.data

object RussianApps {
    private val packages = setOf(
        "ru.yandex.search", "ru.yandex.searchplugin", "ru.yandex.yandexnavi", "ru.yandex.taxi",
        "ru.yandex.market", "ru.yandex.music", "ru.yandex.mail", "ru.yandex.disk", "ru.yandex.bus",
        "ru.yandex.metro", "ru.yandex.translate", "ru.yandex.browser", "ru.yandex.weather", "ru.yandex.food",
        "ru.yandex.drive", "ru.yandex.kino", "com.yandex.browser", "com.yandex.mobile.email", "com.yandex.disk",
        "com.yandex.map", "com.yandex.transport", "com.yandex.market", "com.yandex.music", "com.yandex.tv",
        "com.vkontakte.android", "com.vk.im", "com.vk.admin", "com.vk.music", "com.vk.calls", "com.vk.video",
        "ru.mail.mailapp", "ru.mail.calendar", "ru.mail.cloud", "ru.mail.agent", "ru.ok.android",
        "com.idamob.tinkoff.android", "com.tinkoff.mobile", "ru.sberbankmobile", "ru.sberbank.investing",
        "com.alfabank.mobile", "com.alfabank.mobile.android", "ru.vtb24.mobilebanking.android", "com.openbank",
        "ru.gazprombank.android", "com.raiffeisen.mobile", "com.sovkombank.mobile", "ru.mts.mymts",
        "ru.mts_bank.android", "com.megafon.mymobile", "com.beeline.my", "ru.tele2.mytele2",
        "ru.gov.gosuslugi", "ru.gosuslugi", "com.gosuslugi.android", "ru.mosmetro.mobile", "ru.mos.mobile",
        "com.avito.android", "com.wildberries.ru", "com.ozon.app", "ru.ozon.app.android", "com.lamoda.lite",
        "ru.rutube.app", "ru.kinopoisk", "ru.kinopoisk.mobile", "ru.start.android", "com.more.tv",
        "ru.ivi", "ru.ivi.video", "ru.tv.ivi", "ru.sberfood", "com.deliveryclub", "ru.foodfox.client",
        "ru.samokat", "ru.lavka", "ru.pyaterochka.app", "com.magnit.mobile", "com.dnsShop.android",
        "ru.dns_shop", "com.citilink", "ru.sportmaster.app",
        "ru.yandex.yandexmaps", "ru.yandex.taxiservice", "ru.yandex.go", "ru.yandex.scooter",
        "ru.yandex.rasp", "ru.yandex.afisha", "ru.yandex.realty", "ru.yandex.auto", "ru.yandex.pogoda",
        "com.yandex.go", "com.yandex.taximeter", "com.yandex.rasp", "com.yandex.launcher",
        "com.vk.vkvideo", "com.vk.calls", "com.vk.work", "com.vk.quiz", "com.vk.polls",
        "ru.mail.my", "ru.mail.games", "ru.mail.news", "ru.mail.pogoda", "ru.mail.search",
        "ru.sberbank.sdktablet", "ru.sberbankmobile.lite", "ru.sberbank.spasibo", "ru.sber.devices",
        "ru.sberbank.investments", "ru.sber.sberprime", "ru.sber.sbermarket", "ru.sber.sbermegamarket",
        "com.idamob.tinkoff.investing", "com.tinkoff.investing", "com.tinkoff.insurance",
        "ru.alfabank.mobile.android", "ru.alfabank.mobile", "com.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android", "ru.vtb24.mobile", "com.vtb.mobilebanking",
        "com.psbank.robo", "ru.mts.mymts", "ru.mts.bank", "ru.mts.tv", "ru.mts.music",
        "com.megafon.tv", "ru.beeline.services", "ru.beeline.my", "ru.tele2.mytele2.new",
        "ru.rostel", "ru.gosuslugi.auto", "ru.gosuslugi.goskey",
        "ru.rt.android.mobile", "ru.domru.mobile", "ru.ertelecom.mobile",
        "ru.gosuslugi", "ru.nalog.fns", "ru.mos.ru", "ru.mosmetro.mobile", "ru.mos.parking",
        "com.avito.android", "com.wildberries.ru", "ru.wildberries", "com.ozon.app",
        "ru.ozon.app.android", "ru.ozon.bank", "ru.ozon.travel", "com.lamoda.lite",
        "ru.rutube.app", "ru.kinopoisk", "ru.kinopoisk.mobile", "ru.start.android",
        "ru.ivi", "ru.ivi.video", "ru.tv.ivi", "com.more.tv", "ru.okko.tv", "ru.wink",
        "ru.sberfood", "ru.sbermarket", "com.deliveryclub", "ru.foodfox.client",
        "ru.samokat", "ru.lavka", "ru.pyaterochka.app", "com.magnit.mobile",
        "ru.lenta.mobile", "ru.auchan", "ru.vkusvill", "ru.dixy", "ru.fixprice",
        "com.dnsShop.android", "ru.dns_shop", "com.citilink", "ru.mvideo", "ru.eldorado",
        "ru.sportmaster.app", "ru.detmir", "ru.leroymerlin", "ru.petrovich",
        "ru.aeroflot", "com.aeroflot", "ru.s7", "ru.pobeda", "ru.utair", "ru.rzd",
        "ru.tutu", "ru.aviasales", "ru.ostrovok", "ru.onetwotrip", "ru.citymobil",
        "ru.max", "ru.max.messenger", "ru.dzen", "ru.yandex.zen",
    )

    private val packagePrefixes = listOf(
        "ru.", "com.yandex.", "com.vk.", "com.vkontakte.", "com.sberbank.", "com.sber.",
        "com.tinkoff.", "com.idamob.tinkoff.", "com.tbank.", "com.alfabank.", "com.vtb.",
        "com.gosuslugi.", "com.avito.", "com.wildberries.", "com.ozon.", "com.lamoda.",
        "com.mts.", "com.megafon.", "com.beeline.", "com.tele2.", "com.rzd.",
        "com.kinopoisk.", "com.rutube.", "com.ivi.", "com.okko.", "com.more.tv",
    )

    private val labelMarkers = listOf(
        "яндекс", "yandex", "госуслуги", "сбер", "sber", "т-банк", "тинькофф", "tinkoff",
        "альфа-банк", "альфа–банк", "втб", "vtb", "вконтакте", "vk video", "vk music",
        "авито", "avito", "wildberries", "озон", "ozon", "ламода", "lamoda",
        "мтс", "мегафон", "билайн", "beeline", "tele2", "ржд", "rzd",
        "кинопоиск", "rutube", "одноклассники", "дзен", "почта россии",
    )

    fun installedMatches(installedPackages: Collection<String>): Set<String> =
        installedPackages.filterTo(mutableSetOf()) { matchesPackage(it) }

    fun installedMatches(installedApps: List<Pair<String, String>>): Set<String> =
        installedApps.filter { matchesPackage(it.first) || matchesLabel(it.second) }.mapTo(mutableSetOf()) { it.first }

    private fun matchesPackage(packageName: String): Boolean =
        packageName in packages || packagePrefixes.any(packageName::startsWith)

    private fun matchesLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return labelMarkers.any { normalized.contains(it) } ||
            Regex("(^|\\s)(vk|ivi|okko|wink|max)(\\s|$)").containsMatchIn(normalized)
    }
}

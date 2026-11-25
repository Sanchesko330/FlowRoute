package dev.flowroute.app.data

object WhitelistPreset {
    val domains = listOf(
        "domain:gosuslugi.ru", "domain:gosweb.ru", "domain:government.ru", "domain:kremlin.ru",
        "domain:nalog.ru", "domain:cbr.ru", "domain:mirpayment.ru", "domain:nspk.ru",
        "domain:vk.com", "domain:vk.ru", "domain:ok.ru", "domain:mail.ru", "domain:max.ru", "domain:dzen.ru",
        "domain:yandex.ru", "domain:yandex.net", "domain:ya.ru",
        "domain:ozon.ru", "domain:wildberries.ru", "domain:avito.ru",
        "domain:rutube.ru", "domain:kinopoisk.ru", "domain:2gis.ru", "domain:2gis.com",
        "domain:alfabank.ru", "domain:vtb.ru", "domain:gazprombank.ru", "domain:tbank.ru", "domain:sberbank.ru",
        "domain:mts.ru", "domain:megafon.ru", "domain:beeline.ru", "domain:tele2.ru", "domain:rt.ru",
        "domain:magnit.ru", "domain:samokat.ru", "domain:5ka.ru", "domain:perekrestok.ru",
        "domain:lenta.com", "domain:vkusvill.ru",
        "domain:rzd.ru", "domain:aeroflot.ru", "domain:pobeda.aero", "domain:tutu.ru",
        "domain:cdek.ru", "domain:russianpost.ru", "domain:hh.ru",
    )

    private val packages = setOf(
        "ru.rostel", "ru.gosuslugi.auto", "ru.gosuslugi.goskey",
        "ru.gosuslugi", "ru.gov.gosuslugi", "com.gosuslugi.android", "ru.nalog.fns",
        "com.vkontakte.android", "com.vk.im", "com.vk.calls", "com.vk.video", "com.vk.vkvideo",
        "ru.ok.android", "ru.mail.mailapp", "ru.mail.my", "ru.max", "ru.max.messenger", "ru.dzen",
        "ru.yandex.search", "ru.yandex.searchplugin", "ru.yandex.yandexnavi", "ru.yandex.yandexmaps",
        "ru.yandex.taxi", "ru.yandex.go", "ru.yandex.market", "ru.yandex.music", "ru.yandex.mail",
        "ru.yandex.disk", "ru.yandex.bus", "ru.yandex.metro", "ru.yandex.food", "ru.yandex.lavka",
        "com.yandex.browser", "com.yandex.go", "com.yandex.market", "com.yandex.music",
        "com.ozon.app", "ru.ozon.app.android", "com.wildberries.ru", "ru.wildberries", "com.avito.android",
        "ru.rutube.app", "ru.kinopoisk", "ru.kinopoisk.mobile", "ru.dublgis.dgismobile", "com.2gis",
        "com.alfabank.mobile", "com.alfabank.mobile.android", "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android", "ru.vtb24.mobile", "com.vtb.mobilebanking",
        "ru.gazprombank.android", "com.idamob.tinkoff.android", "com.tinkoff.mobile", "ru.sberbankmobile",
        "ru.mts.mymts", "com.megafon.mymobile", "com.beeline.my", "ru.beeline.my",
        "ru.tele2.mytele2", "ru.tele2.mytele2.new", "ru.rt.android.mobile",
        "com.magnit.mobile", "ru.samokat", "ru.lavka", "ru.pyaterochka.app", "ru.lenta.mobile", "ru.vkusvill",
        "ru.rzd", "com.rzd", "ru.aeroflot", "com.aeroflot", "ru.pobeda", "ru.tutu", "ru.hh.android",
    )

    fun installedMatches(installedApps: List<Pair<String, String>>): Set<String> =
        installedApps.filter { it.first in packages }.mapTo(mutableSetOf()) { it.first }

    fun essentialDirectMatches(installedPackages: Collection<String>): Set<String> = installedPackages.filterTo(mutableSetOf()) {
        it == "ru.rostel" || it == "ru.gosuslugi.auto" || it == "ru.gosuslugi.goskey"
    }
}

package dev.flowroute.app.data

object BlockedApps {
    private val packages = setOf(
        // Браузеры.
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus", "org.mozilla.fenix",
        "com.microsoft.emmx", "com.microsoft.emmx.beta", "com.microsoft.emmx.dev", "com.microsoft.emmx.canary",
        "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native", "com.opera.gx",
        "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.vivaldi.browser.snapshot",
        "org.torproject.torbrowser", "ru.yandex.browser", "com.yandex.browser",

        // ИИ-сервисы.
        "com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard",
        "ai.perplexity.app.android", "com.microsoft.copilot", "ai.x.grok",

        // Музыка, видео и стриминг.
        "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.spotify.music", "com.soundcloud.android", "deezer.android.app",
        "com.netflix.mediaclient", "tv.twitch.android.app", "com.zhiliaoapp.musically",
        "com.amazon.avod.thirdpartyclient",

        // Соцсети и мессенджеры.
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca",
        "com.twitter.android", "com.discord", "org.thoughtcrime.securesms",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "nekox.messenger", "tw.nekomimi.nekogram", "org.telegram.plus", "com.exteragram.messenger",
        "com.reddit.frontpage", "com.linkedin.android", "com.snapchat.android",
        "com.viber.voip", "com.pinterest", "com.patreon.android",

        // Игры.
        "com.roblox.client", "com.supercell.brawlstars", "com.epicgames.fortnite",

        // Работа и обучение с ограничениями для пользователей из РФ.
        "com.notion.id", "com.slack", "com.trello", "com.coursera.android",
        "com.airbnb.android", "com.booking",
    )

    fun installedMatches(installedApps: List<Pair<String, String>>): Set<String> =
        installedApps.filter { it.first in packages }.mapTo(mutableSetOf()) { it.first }
}

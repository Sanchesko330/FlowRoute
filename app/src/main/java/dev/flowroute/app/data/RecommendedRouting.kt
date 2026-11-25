package dev.flowroute.app.data

object RecommendedRouting {
    fun settings(installedApps: List<Pair<String, String>>): AppSettings = AppSettings(
        regionProfile = RegionRouteProfile.BLOCKED_VPN,
        appFilterEnabled = true,
        appFilterMode = AppFilterMode.INCLUDE_SELECTED,
        selectedApps = BlockedApps.installedMatches(installedApps),
        qwdttRegionProfile = RegionRouteProfile.ALL_VPN,
        qwdttAppFilterEnabled = true,
        qwdttAppFilterMode = AppFilterMode.EXCLUDE_SELECTED,
        qwdttSelectedApps = WhitelistPreset.installedMatches(installedApps),
    )
}

package dev.flowroute.app.data

object FailoverPolicy {
    const val STABLE_CHECK_INTERVAL_MS = 5 * 60_000L
    const val FAILURE_CHECK_INTERVAL_MS = 60_000L

    fun nextState(previousFailures: Int, successful: Boolean): Pair<Int, Boolean> {
        if (successful) return 0 to true
        val failures = previousFailures + 1
        return failures to (failures < 2)
    }

    fun checkInterval(servers: List<ServerNode>): Long =
        if (servers.any { it.enabled && it.available == false }) FAILURE_CHECK_INTERVAL_MS else STABLE_CHECK_INTERVAL_MS

    fun normalizePingDelay(delayMs: Int): Int = if (delayMs in 0 until 10_000) delayMs else -1

    fun coreRouteKey(state: AppState): List<String> {
        val group = state.activeGroup()
        return listOf(group?.id.orEmpty(), group?.qwdttOnly.toString(), group?.useQwdttFallback.toString(), state.settings.toString()) +
            state.routes.filter { it.enabled }.map { "${it.id}:${it.action}:${it.values}:${it.serverId}:${it.groupId}" } +
            state.activeServers().filter { it.enabled }.map { it.id }.sorted()
    }
}

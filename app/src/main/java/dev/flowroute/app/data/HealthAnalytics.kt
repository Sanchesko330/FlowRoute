package dev.flowroute.app.data

object HealthAnalytics {
    data class Summary(
        val checks: Int,
        val availabilityPercent: Int,
        val downtimeMillis: Long,
        val currentlyDown: Boolean,
    )

    fun summarize(samples: List<HealthSample>, now: Long): Summary {
        val ordered = samples.sortedBy { it.timestamp }
        if (ordered.isEmpty()) return Summary(0, 0, 0, false)
        var downtime = 0L
        var downSince: Long? = null
        ordered.forEach { sample ->
            if (!sample.available && downSince == null) downSince = sample.timestamp
            if (sample.available) downSince?.let { start ->
                downtime += (sample.timestamp - start).coerceAtLeast(0)
                downSince = null
            }
        }
        downSince?.let { downtime += (now - it).coerceAtLeast(0) }
        val available = ordered.count { it.available }
        return Summary(
            checks = ordered.size,
            availabilityPercent = (available * 100.0 / ordered.size).toInt(),
            downtimeMillis = downtime,
            currentlyDown = !ordered.last().available,
        )
    }
}

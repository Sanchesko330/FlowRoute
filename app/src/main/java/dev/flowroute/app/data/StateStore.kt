package dev.flowroute.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class StateStore(context: Context) {
    private val preferences = context.getSharedPreferences("flowroute_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    @Synchronized
    fun update(transform: (AppState) -> AppState) {
        val updated = transform(mutableState.value)
        preferences.edit().putString(KEY_STATE, json.encodeToString(AppState.serializer(), updated)).apply()
        mutableState.value = updated
    }

    fun recordError(source: String, error: Throwable) {
        val entry = AppLogEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = source,
            message = error.message ?: error.javaClass.simpleName,
        )
        update { it.copy(errorLogs = (it.errorLogs + entry).takeLast(300)) }
    }

    fun recordHealth(samples: List<HealthSample>) {
        if (samples.isEmpty()) return
        update { it.copy(healthSamples = (it.healthSamples + samples).takeLast(5000)) }
    }

    fun clearErrors() = update { it.copy(errorLogs = emptyList()) }

    private fun load(): AppState {
        val raw = preferences.getString(KEY_STATE, null) ?: return AppState()
        val saved = runCatching { json.decodeFromString(AppState.serializer(), raw) }.getOrElse { AppState() }
        val migratedGroups = saved.groups.ifEmpty {
            saved.subscriptions.map { ServerGroup(it.id, it.name, it.updatedAt) }
        }
        val migratedServers = saved.servers.map { server ->
            server.copy(
                groupId = server.groupId.ifBlank { server.subscriptionId },
                host = server.outbound.endpointHost() ?: server.host,
                port = server.outbound.endpointPort() ?: server.port,
            )
        }
        val selectedGroupId = saved.selectedGroupId?.takeIf { id -> migratedGroups.any { it.id == id } }
            ?: migratedServers.firstOrNull { it.enabled }?.groupId ?: migratedGroups.firstOrNull()?.id
        return saved.copy(groups = migratedGroups, servers = migratedServers, selectedGroupId = selectedGroupId)
    }

    private companion object { const val KEY_STATE = "state_v1" }
}

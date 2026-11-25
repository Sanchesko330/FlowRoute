package dev.flowroute.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ConfigurationBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val state: AppState,
) {
    companion object {
        const val FORMAT = "flowroute-configuration"
        const val VERSION = 1
    }
}

object ConfigurationBackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(state: AppState): String = json.encodeToString(
        ConfigurationBackup.serializer(),
        ConfigurationBackup(state = state),
    )

    fun decode(raw: String): AppState {
        val backup = try {
            json.decodeFromString(ConfigurationBackup.serializer(), raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Файл не является резервной копией FlowRoute", error)
        }
        require(backup.format == ConfigurationBackup.FORMAT) { "Файл не является резервной копией FlowRoute" }
        require(backup.version == ConfigurationBackup.VERSION) { "Эта версия резервной копии не поддерживается" }
        return backup.state
    }
}

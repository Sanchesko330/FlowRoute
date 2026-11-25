package dev.flowroute.app.core

import android.content.Context
import dev.flowroute.app.data.QwdttConfig
import dev.flowroute.app.data.QwdttRuntimeState
import dev.flowroute.app.data.QwdttStatus
import dev.flowroute.app.data.peerWithPort
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object QwdttEngine {
    private const val SOCKS_PORT = 49500
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val mutableState = MutableStateFlow(QwdttRuntimeState())
    val state = mutableState.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var reader: kotlinx.coroutines.Job? = null
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var generation = 0L
    @Volatile var activeSocksPort: Int? = null
        private set

    suspend fun start(
        context: Context,
        config: QwdttConfig,
        log: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnected: (String) -> Unit,
    ): Result<Int> = startMutex.withLock {
        if (activeSocksPort != null && mutableState.value.configId == config.id) return Result.success(SOCKS_PORT)
        if (starting) return Result.failure(IllegalStateException("QWDTT уже запускается"))
        starting = true
        stopping = false
        val startGeneration = ++generation
        stopProcess()
        mutableState.value = QwdttRuntimeState(QwdttStatus.CHECKING_DNS, "Проверка DNS", config.id)
        val ready = CompletableDeferred<Unit>()
        try {
            val preflight = QwdttPreflight.run(context, log)
            if (startGeneration != generation) error("Запуск QWDTT отменён")
            mutableState.value = QwdttRuntimeState(QwdttStatus.CHECKING_VK, preflight.detail, config.id)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libqwdtt.so")
            if (!binary.exists()) error("QWDTT-движок отсутствует в этой сборке")
            val command = mutableListOf(
                binary.absolutePath,
                "-peer", config.peerWithPort,
                "-vk", config.hashes,
                "-n", config.workers.toString(),
                "-listen", "127.0.0.1:${config.port}",
                "-device-id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "flowroute",
                "-password", config.password,
                "-captcha-mode", "auto",
                "-vk-auth", "anonymous",
                "-go-dns", preflight.dnsArgument,
                "-obfs", "audio",
                "-turn-transport", "udp",
                "-mode", "socks",
                "-socks", "127.0.0.1:$SOCKS_PORT",
            )
            networkDnsArgument(context)?.let { command.add("-dns-fallback"); command.add(it) }
            mutableState.value = QwdttRuntimeState(QwdttStatus.STARTING, "Получение TURN и запуск канала", config.id)
            log("QWDTT: запуск резервного канала")
            val builder = ProcessBuilder(command).directory(context.filesDir).redirectErrorStream(true)
            builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val started = builder.start()
            process = started
            reader = scope.launch {
                try {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (startGeneration != generation) return@forEach
                            val clean = line.take(500)
                            when {
                                clean.contains("[SOCKS] listening") -> {
                                    activeSocksPort = SOCKS_PORT
                                    mutableState.value = QwdttRuntimeState(QwdttStatus.CONNECTED, "Резервный канал активен", config.id)
                                    log("QWDTT: SOCKS5 готов")
                                    ready.complete(Unit)
                                }
                                clean.contains("VK", true) && !ready.isCompleted -> updateStarting("Подключение к VK", config.id)
                                clean.contains("WRAP", true) && !ready.isCompleted -> updateStarting("Установка защищённого канала", config.id)
                            }
                            if (isErrorLine(clean)) {
                                log("QWDTT: $clean")
                                onError(IllegalStateException(clean))
                                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(clean))
                            }
                        }
                    }
                    val exit = runCatching { started.waitFor() }.getOrDefault(-1)
                    if (startGeneration == generation && mutableState.value.configId == config.id) {
                        activeSocksPort = null
                        val message = "QWDTT-процесс завершился (код $exit)"
                        mutableState.value = QwdttRuntimeState(QwdttStatus.ERROR, message, config.id)
                        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(message)) else onDisconnected(message)
                    }
                } catch (error: Throwable) {
                    if (startGeneration == generation && !ready.isCompleted) ready.completeExceptionally(error)
                }
            }
            val completed = withTimeoutOrNull(120_000L) { ready.await() }
            if (completed == null) error("Таймаут запуска QWDTT (120 секунд)")
            return Result.success(SOCKS_PORT)
        } catch (error: Throwable) {
            if (startGeneration == generation) {
                activeSocksPort = null
                stopProcess()
                mutableState.value = QwdttRuntimeState(QwdttStatus.ERROR, error.message ?: "Ошибка QWDTT", config.id)
                onError(error)
            }
            return Result.failure(error)
        } finally {
            starting = false
        }
    }

    suspend fun ping(context: Context, config: QwdttConfig, log: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val preflight = QwdttPreflight.run(context, log)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libqwdtt.so")
            if (!binary.exists()) error("QWDTT-движок отсутствует в этой сборке")
            val started = ProcessBuilder(
                binary.absolutePath,
                "-peer", config.peerWithPort,
                "-vk", config.hashes,
                "-n", config.workers.toString(),
                "-device-id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "flowroute",
                "-password", config.password,
                "-captcha-mode", "auto",
                "-vk-auth", "anonymous",
                "-go-dns", preflight.dnsArgument,
                "-ping-only",
            ).directory(context.filesDir).redirectErrorStream(true).start()
            val output = StringBuilder()
            val readerJob = scope.launch {
                started.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } }
            }
            try {
                if (!started.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) error("Таймаут проверки QWDTT (120 секунд)")
                withTimeoutOrNull(5_000L) { readerJob.join() }
                val text = output.toString()
                if (started.exitValue() != 0) error(text.lineSequence().lastOrNull { it.contains("PING_ERROR|") }
                    ?.substringAfter("PING_ERROR|") ?: "QWDTT недоступен")
                Regex("PING_RESULT\\|(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
                    ?: error("QWDTT не вернул задержку")
            } finally {
                readerJob.cancel()
                started.destroy()
            }
        }
    }

    fun stop() {
        generation++
        stopping = true
        activeSocksPort = null
        stopProcess()
        mutableState.value = QwdttRuntimeState()
    }

    private fun stopProcess() {
        runCatching { process?.outputStream?.bufferedWriter()?.use { it.write("STOP\n"); it.flush() } }
        process?.destroy()
        process = null
        reader?.cancel()
        reader = null
    }

    private fun updateStarting(detail: String, configId: String) {
        mutableState.value = QwdttRuntimeState(QwdttStatus.STARTING, detail, configId)
    }

    private fun isErrorLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("fatal") || lower.contains("ошибка") || lower.contains("captcha_required") ||
            lower.contains("wrap_auth_timeout") || lower.contains("все воркеры завершены")
    }

    private fun networkDnsArgument(context: Context): String? {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val servers = manager.allNetworks.flatMap { manager.getLinkProperties(it)?.dnsServers.orEmpty() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
        return servers.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}

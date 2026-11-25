package dev.flowroute.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.flowroute.app.FlowRouteApp
import dev.flowroute.app.MainActivity
import dev.flowroute.app.R
import dev.flowroute.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FlowRouteVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coreMutex = Mutex()
    private var tunnel: ParcelFileDescriptor? = null
    private var activeChannelQwdtt = false
    private var activeTunnelKey = ""
    private var statsJob: kotlinx.coroutines.Job? = null
    private var statsWatchdogJob: kotlinx.coroutines.Job? = null
    private var failoverJob: kotlinx.coroutines.Job? = null
    private var healthJob: kotlinx.coroutines.Job? = null
    private var fallbackJob: kotlinx.coroutines.Job? = null
    private var recoveryChecks = 0
    private var nextFallbackAttemptAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR_DNS_CACHE) {
            if (status.value == VpnStatus.CONNECTED) scope.launch { restartCoreForFailover(); log("DNS-кэш очищен") }
            else XrayGateway.resetDnsCache()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (status.value == VpnStatus.CONNECTING) return START_NOT_STICKY
        if (status.value == VpnStatus.CONNECTED) return START_STICKY
        startForegroundNotification(); scope.launch { startTunnel() }; return START_STICKY
    }

    private fun tunnelKey(settings: AppSettings, channel: RoutingChannel): String = listOf(
        settings.ipv6Enabled.toString(),
        settings.appFilterEnabled(channel).toString(),
        settings.appFilterMode(channel).name,
        settings.selectedApps(channel).sorted().joinToString(","),
    ).joinToString("|")

    private fun createTunnel(state: AppState, qwdttActive: Boolean): ParcelFileDescriptor {
        val settings = state.settings
        val channel = if (qwdttActive) RoutingChannel.QWDTT else RoutingChannel.VLESS
        val builder = Builder().setSession("FlowRoute").setMtu(1500)
            .addAddress("172.19.0.2", 30).addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1").addDisallowedApplication(packageName)
        if (settings.ipv6Enabled) builder.addAddress("fdfe:dcba:9876::2", 126).addRoute("::", 0).addDnsServer("2606:4700:4700::1111")
        if (settings.appFilterEnabled(channel)) settings.selectedApps(channel).forEach { app ->
            runCatching { when (settings.appFilterMode(channel)) {
                AppFilterMode.EXCLUDE_SELECTED -> builder.addDisallowedApplication(app)
                AppFilterMode.INCLUDE_SELECTED -> builder.addAllowedApplication(app)
            } }
        }
        ANDROID_AUTO_PACKAGES.forEach { app ->
            if (runCatching { packageManager.getPackageInfo(app, 0) }.isSuccess) runCatching { builder.addDisallowedApplication(app) }
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false).setBlocking(true)
        return builder.establish() ?: error("Android не создал VPN-интерфейс")
    }

    private suspend fun startTunnel() {
        mutableStatus.value = VpnStatus.CONNECTING; mutableLastError.value = ""
        try {
            if (prepare(this) != null) error("Нет разрешения на VPN")
            val state = (application as FlowRouteApp).store.state.value
            activeChannelQwdtt = state.activeGroup()?.qwdttOnly == true || QwdttEngine.activeSocksPort != null
            val channel = if (activeChannelQwdtt) RoutingChannel.QWDTT else RoutingChannel.VLESS
            if (state.settings.regionProfile(channel).needsRegionalData()) RegionalDataManager.ensure(this)
            tunnel = createTunnel(state, activeChannelQwdtt)
            activeTunnelKey = tunnelKey(state.settings, channel)
            XrayGateway.prepareDns(this, state.settings.dnsProvider)
            if (state.activeGroup()?.qwdttOnly == true && !activateFallback(state.activeGroup()!!.id)) {
                error("Не удалось запустить QWDTT")
            }
            val processUids = if (state.settings.stickySessions) packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName }.associate { it.packageName to it.uid } else emptyMap()
            val assetPath = if (state.settings.regionProfile(channel).needsRegionalData()) RegionalDataManager.assetDir(this).absolutePath else null
            XrayGateway.start(ConfigBuilder.build(state, tunnel!!.fd, processUids, assetPath, QwdttEngine.activeSocksPort)).getOrThrow()
            mutableStatus.value = VpnStatus.CONNECTED
            mutableTraffic.value = emptyMap(); mutableSpeeds.value = emptyMap(); mutableSamples.value = emptyList()
            log("VPN запущен: ${state.activeServers().count { it.enabled }} серверов, ${state.routes.count { it.enabled }} правил")
            startStatsCollection()
            failoverJob = scope.launch { watchFailover() }
            healthJob = scope.launch { watchHealth() }
        } catch (error: Throwable) {
            android.util.Log.e("FlowRouteVpn", "Xray startup failed", error)
            val message = error.message ?: "Не удалось запустить VPN"
            (application as FlowRouteApp).store.recordError("VPN", error)
            mutableLastError.value = message; log("Ошибка запуска: $message")
            shutdown(); mutableStatus.value = VpnStatus.ERROR
        }
    }

    private suspend fun watchFailover() {
        (application as FlowRouteApp).store.state
            .drop(1)
            .map(FailoverPolicy::coreRouteKey)
            .distinctUntilChanged()
            .collect {
                val state = (application as FlowRouteApp).store.state.value
                val group = state.activeGroup()
                if (group?.qwdttOnly == true && QwdttEngine.activeSocksPort == null) requestFallback(group.id)
                else {
                    if (!state.settings.autoFailover && group?.qwdttOnly != true) QwdttEngine.stop()
                    restartCoreForFailover()
                }
            }
    }

    private suspend fun restartCoreForFailover() = coreMutex.withLock {
        val activeTunnel = tunnel ?: return@withLock
        if (status.value != VpnStatus.CONNECTED) return@withLock
        statsJob?.cancel(); statsJob = null
        try {
            val state = (application as FlowRouteApp).store.state.value
            val activeGroupId = state.activeGroup()?.id
            val activeQwdtt = state.qwdttConfigs.firstOrNull { it.id == QwdttEngine.state.value.configId }
            if (activeQwdtt != null && (activeQwdtt.groupId != activeGroupId || state.groups.firstOrNull { it.id == activeGroupId }?.useQwdttFallback != true)) {
                QwdttEngine.stop()
            }
            val qwdttPort = QwdttEngine.activeSocksPort
            if (qwdttPort == null && state.activeGroup()?.qwdttOnly == true) {
                XrayGateway.stop()
                state.activeGroup()?.id?.let(::requestFallback)
                log("QWDTT: ожидание запуска актуальной группы")
                return@withLock
            }
            val qwdttActive = qwdttPort != null
            val channel = if (qwdttActive) RoutingChannel.QWDTT else RoutingChannel.VLESS
            if (state.settings.regionProfile(channel).needsRegionalData()) RegionalDataManager.ensure(this)
            XrayGateway.stop()
            val nextTunnelKey = tunnelKey(state.settings, channel)
            val nextTunnel = if (qwdttActive != activeChannelQwdtt || nextTunnelKey != activeTunnelKey) {
                runCatching { activeTunnel.close() }
                createTunnel(state, qwdttActive).also {
                    tunnel = it
                    activeChannelQwdtt = qwdttActive
                    activeTunnelKey = nextTunnelKey
                }
            } else activeTunnel
            XrayGateway.prepareDns(this, state.settings.dnsProvider)
            val processUids = if (state.settings.stickySessions) packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName }.associate { it.packageName to it.uid } else emptyMap()
            val assetPath = if (state.settings.regionProfile(channel).needsRegionalData()) RegionalDataManager.assetDir(this).absolutePath else null
            XrayGateway.start(ConfigBuilder.build(state, nextTunnel.fd, processUids, assetPath, qwdttPort)).getOrThrow()
            log(if (qwdttPort == null) "Failover: маршруты обновлены без перезапуска VPN" else "QWDTT: трафик переведён на резервный канал")
            startStatsCollection()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            (application as FlowRouteApp).store.recordError("Failover", error)
            mutableLastError.value = error.message ?: "Ошибка переключения сервера"
            log("Ошибка failover: ${mutableLastError.value}")
            shutdown()
            mutableStatus.value = VpnStatus.ERROR
        }
    }

    private suspend fun watchHealth() {
        delay(5_000L)
        while (true) {
            if (status.value != VpnStatus.CONNECTED) return
            val store = (application as FlowRouteApp).store
            val state = store.state.value
            val now = System.currentTimeMillis()
            val group = state.activeGroup()
            if (group?.qwdttOnly == true) {
                if (QwdttEngine.activeSocksPort == null && now >= nextFallbackAttemptAt) requestFallback(group.id)
                delay(FailoverPolicy.FAILURE_CHECK_INTERVAL_MS)
                continue
            }
            val enabled = state.activeServers().filter { it.enabled }
            if (enabled.isNotEmpty()) {
                val delays = XrayGateway.queryActiveLatencies(state)
                store.update { current -> current.copy(servers = current.servers.map { server ->
                    val index = enabled.indexOfFirst { it.id == server.id }
                    if (index < 0) return@map server
                    val delayMs = delays.getOrNull(index) ?: -1
                    val (failures, available) = FailoverPolicy.nextState(server.failedChecks, delayMs >= 0)
                    server.copy(latencyMs = delayMs, available = available, failedChecks = failures, checkedAt = now)
                }) }
                store.recordHealth(enabled.mapIndexed { index, server -> HealthSample(server.id, now, delays.getOrNull(index) ?: -1) })

                val updated = store.state.value
                val updatedGroup = updated.activeGroup()
                val config = updated.qwdttConfigs.firstOrNull { it.groupId == updatedGroup?.id }
                val healthy = updated.activeServers().filter { it.enabled && it.available != false }
                val allUnavailable = enabled.isNotEmpty() && healthy.isEmpty()
                if (updated.settings.autoFailover && updatedGroup?.useQwdttFallback == true && config != null && allUnavailable && QwdttEngine.activeSocksPort == null) {
                    recoveryChecks = 0
                    if (now >= nextFallbackAttemptAt) requestFallback(updatedGroup.id)
                } else if (QwdttEngine.activeSocksPort != null && healthy.isNotEmpty()) {
                    recoveryChecks += 1
                    if (recoveryChecks >= 3) {
                        recoveryChecks = 0
                        QwdttEngine.stop()
                        restartCoreForFailover()
                        log("QWDTT: VLESS восстановлены, резерв остановлен")
                    }
                } else {
                    recoveryChecks = 0
                }
            }
            delay(FailoverPolicy.checkInterval(store.state.value.activeServers()))
        }
    }

    private fun requestFallback(groupId: String) {
        if (fallbackJob?.isActive == true || status.value != VpnStatus.CONNECTED) return
        fallbackJob = scope.launch { activateFallback(groupId) }
    }

    private suspend fun activateFallback(groupId: String): Boolean {
        val state = (application as FlowRouteApp).store.state.value
        val config = state.qwdttConfigs.firstOrNull { it.groupId == groupId } ?: return false
        val only = state.groups.firstOrNull { it.id == groupId }?.qwdttOnly == true
        log(if (only) "QWDTT: запуск основного канала" else "QWDTT: все VLESS недоступны, начинаем проверки резерва")
        var started = false
        QwdttEngine.start(this, config,
            log = ::log,
            onError = { (application as FlowRouteApp).store.recordError("QWDTT", it) },
            onDisconnected = { message ->
                log(message)
                scope.launch {
                    if (status.value != VpnStatus.CONNECTED) return@launch
                    val group = (application as FlowRouteApp).store.state.value.activeGroup()
                    if (group?.qwdttOnly == true && group.id == groupId) {
                        delay(5_000L)
                        val currentGroup = (application as FlowRouteApp).store.state.value.activeGroup()
                        if (status.value == VpnStatus.CONNECTED && currentGroup?.qwdttOnly == true && currentGroup.id == groupId && QwdttEngine.activeSocksPort == null) {
                            requestFallback(groupId)
                        }
                    } else restartCoreForFailover()
                }
            },
        ).onSuccess {
            started = true
            nextFallbackAttemptAt = 0L
            if (status.value == VpnStatus.CONNECTED) restartCoreForFailover()
        }.onFailure {
            nextFallbackAttemptAt = System.currentTimeMillis() + 60_000L
            log("QWDTT: ${it.message ?: "не удалось запустить канал"}")
        }
        return started
    }

    private fun startStatsCollection() {
        lastStatsSuccessAt = System.currentTimeMillis()
        statsJob?.cancel()
        statsJob = scope.launch { collectStats() }
        if (statsWatchdogJob?.isActive == true) return
        statsWatchdogJob = scope.launch {
            while (true) {
                delay(5_000L)
                val statsTimeout = if (FlowRouteApp.appInForeground.value) 10_000L else 45_000L
                if (status.value == VpnStatus.CONNECTED && System.currentTimeMillis() - lastStatsSuccessAt > statsTimeout) {
                    log("Статистика: перезапуск после таймаута")
                    startStatsCollection()
                }
            }
        }
    }

    private suspend fun collectStats() {
        var previous: Map<String, TrafficCounter>? = null
        var previousTime = 0L
        while (true) {
            val now = System.currentTimeMillis()
            XrayGateway.queryTraffic().onSuccess { counters ->
                lastStatsSuccessAt = now
                mutableTraffic.value = counters
                val old = previous
                if (old != null && now > previousTime) {
                    val seconds = (now - previousTime) / 1000.0
                    mutableSpeeds.value = (counters.keys + old.keys).associateWith { tag ->
                        val current = counters[tag] ?: TrafficCounter()
                        val before = old[tag] ?: TrafficCounter()
                        TrafficCounter(
                            uplink = ((current.uplink - before.uplink) / seconds).toLong().coerceAtLeast(0),
                            downlink = ((current.downlink - before.downlink) / seconds).toLong().coerceAtLeast(0),
                        )
                    }
                    val up = ((counters.values.sumOf { it.uplink } - old.values.sumOf { it.uplink }) / seconds).toLong().coerceAtLeast(0)
                    val down = ((counters.values.sumOf { it.downlink } - old.values.sumOf { it.downlink }) / seconds).toLong().coerceAtLeast(0)
                    val sample = TrafficSample(now, counters.values.sumOf { it.total }, up, down)
                    mutableSamples.value = (mutableSamples.value + sample).takeLast(90)
                }
                previous = counters; previousTime = now
            }.onFailure { android.util.Log.w("FlowRouteVpn", "Stats query failed", it) }
            val wasInForeground = FlowRouteApp.appInForeground.value
            kotlinx.coroutines.withTimeoutOrNull(if (wasInForeground) 2_000L else 30_000L) {
                FlowRouteApp.appInForeground.first { it != wasInForeground }
            }
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, FlowRouteVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.vpn_notification_title)).setContentText("Маршрутизация и мониторинг активны")
            .setContentIntent(openIntent).setOngoing(true).addAction(0, getString(R.string.vpn_stop), stopIntent).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun shutdown() {
        failoverJob?.cancel(); failoverJob = null
        healthJob?.cancel(); healthJob = null
        fallbackJob?.cancel(); fallbackJob = null
        statsWatchdogJob?.cancel(); statsWatchdogJob = null
        statsJob?.cancel(); statsJob = null
        QwdttEngine.stop()
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            XrayGateway.stop(); runCatching { tunnel?.close() }; tunnel = null
            mutableStatus.value = VpnStatus.DISCONNECTED; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    override fun onRevoke() = shutdown()
    override fun onDestroy() {
        QwdttEngine.stop()
        runCatching { tunnel?.close() }; scope.cancel(); mutableStatus.value = VpnStatus.DISCONNECTED; super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "vpn"; private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "dev.flowroute.app.STOP"
        const val ACTION_CLEAR_DNS_CACHE = "dev.flowroute.app.CLEAR_DNS_CACHE"
        private val ANDROID_AUTO_PACKAGES = listOf("com.google.android.projection.gearhead", "com.google.android.gms")
        @Volatile private var lastStatsSuccessAt = 0L
        private val mutableStatus = MutableStateFlow(VpnStatus.DISCONNECTED)
        private val mutableLastError = MutableStateFlow("")
        private val mutableTraffic = MutableStateFlow<Map<String, TrafficCounter>>(emptyMap())
        private val mutableSpeeds = MutableStateFlow<Map<String, TrafficCounter>>(emptyMap())
        private val mutableSamples = MutableStateFlow<List<TrafficSample>>(emptyList())
        private val mutableDiagnostics = MutableStateFlow<List<String>>(emptyList())
        val status = mutableStatus.asStateFlow(); val lastError = mutableLastError.asStateFlow()
        val traffic = mutableTraffic.asStateFlow(); val speeds = mutableSpeeds.asStateFlow(); val samples = mutableSamples.asStateFlow()
        val diagnostics = mutableDiagnostics.asStateFlow()

        private fun log(message: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            mutableDiagnostics.value = (mutableDiagnostics.value + "$time  $message").takeLast(80)
        }

        fun start(context: Context) = context.startForegroundService(Intent(context, FlowRouteVpnService::class.java)).let { }
        fun stop(context: Context) { context.startService(Intent(context, FlowRouteVpnService::class.java).setAction(ACTION_STOP)) }
        fun clearDnsCache(context: Context) { context.startService(Intent(context, FlowRouteVpnService::class.java).setAction(ACTION_CLEAR_DNS_CACHE)) }
    }
}

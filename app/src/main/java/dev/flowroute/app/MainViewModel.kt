package dev.flowroute.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.flowroute.app.core.FlowRouteVpnService
import dev.flowroute.app.core.QwdttEngine
import dev.flowroute.app.core.QwdttDeployRequest
import dev.flowroute.app.core.QwdttDeployer
import dev.flowroute.app.core.XrayGateway
import dev.flowroute.app.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as FlowRouteApp).store
    private val importer = SubscriptionImporter()
    private val updateManager = AppUpdateManager(application)
    private var pendingApk: File? = null
    val state = store.state
    private val mutableBusy = MutableStateFlow(false)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableCheckingIps = MutableStateFlow(false)
    private val mutableExternalIps = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableRegionalBusy = MutableStateFlow(false)
    private val mutableUpdate = MutableStateFlow(AppUpdateUiState())
    private var regionalPreloadStarted = false
    val busy = mutableBusy.asStateFlow(); val message = mutableMessage.asStateFlow()
    val checkingIps = mutableCheckingIps.asStateFlow(); val externalIps = mutableExternalIps.asStateFlow()
    val regionalBusy = mutableRegionalBusy.asStateFlow()
    val update = mutableUpdate.asStateFlow()

    init {
        if (store.isFirstLaunch) applyRecommendedRouting(notify = false)
        checkUpdates(manual = false)
        viewModelScope.launch {
            while (true) {
                delay(15 * 60_000L)
                val now = System.currentTimeMillis()
                val due = store.state.value.subscriptions.filter {
                    it.autoRefresh && it.url.isNotBlank() && now - it.updatedAt >= store.state.value.settings.subscriptionRefreshHours * 3_600_000L
                }
                due.forEach { refreshSubscriptionNow(it.id, throwOnError = false) }
            }
        }
    }

    fun checkUpdates(manual: Boolean = true) {
        if (mutableUpdate.value.stage == UpdateStage.CHECKING) return
        val preferences = getApplication<Application>().getSharedPreferences("updates", Application.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - preferences.getLong("lastCheck", 0L) < 12 * 3_600_000L) return
        mutableUpdate.value = AppUpdateUiState(stage = UpdateStage.CHECKING)
        viewModelScope.launch {
            try {
                val release = updateManager.check()
                preferences.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (release == null) {
                    mutableUpdate.value = AppUpdateUiState()
                    if (manual) mutableMessage.value = "Установлена последняя версия ${BuildConfig.VERSION_NAME}"
                } else {
                    pendingApk = null
                    mutableUpdate.value = AppUpdateUiState(stage = UpdateStage.AVAILABLE, release = release)
                }
            } catch (error: Throwable) {
                mutableUpdate.value = AppUpdateUiState(error = error.message ?: "Не удалось проверить обновления")
                if (manual) mutableMessage.value = mutableUpdate.value.error
            }
        }
    }

    fun installUpdate() {
        pendingApk?.let {
            updateManager.install(it)
            return
        }
        val release = mutableUpdate.value.release ?: return
        if (mutableUpdate.value.stage == UpdateStage.DOWNLOADING) return
        mutableUpdate.value = mutableUpdate.value.copy(stage = UpdateStage.DOWNLOADING, error = "")
        viewModelScope.launch {
            try {
                val apk = updateManager.download(release)
                pendingApk = apk
                mutableUpdate.value = mutableUpdate.value.copy(stage = UpdateStage.READY)
                updateManager.install(apk)
            } catch (error: Throwable) {
                mutableUpdate.value = mutableUpdate.value.copy(stage = UpdateStage.AVAILABLE, error = error.message ?: "Не удалось загрузить APK")
            }
        }
    }

    fun dismissUpdate() {
        if (mutableUpdate.value.stage !in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING)) mutableUpdate.value = AppUpdateUiState()
    }

    fun createGroup(name: String) {
        val title = name.trim().ifEmpty { "Группа ${store.state.value.groups.size + 1}" }
        val group = ServerGroup(UUID.randomUUID().toString(), title)
        store.update { it.copy(groups = it.groups + group, selectedGroupId = it.selectedGroupId ?: group.id) }
        mutableMessage.value = "Группа создана"
    }

    fun selectGroup(id: String) {
        require(store.state.value.groups.any { it.id == id }) { "Группа не найдена" }
        store.update { it.copy(selectedGroupId = id) }
    }

    fun importQwdtt(groupId: String, text: String) = launchTask {
        require(store.state.value.groups.any { it.id == groupId }) { "Сначала создайте группу" }
        val config = QwdttParser.parse(text, groupId)
        store.update { current ->
            current.copy(qwdttConfigs = current.qwdttConfigs.filterNot { it.groupId == groupId } + config,
                groups = current.groups.map { if (it.id == groupId) it.copy(useQwdttFallback = true) else it })
        }
        mutableMessage.value = "QWDTT добавлен и включён как резерв"
    }

    fun removeQwdtt(id: String) = store.update { current ->
        val config = current.qwdttConfigs.firstOrNull { it.id == id }
        current.copy(qwdttConfigs = current.qwdttConfigs.filterNot { it.id == id }, groups = current.groups.map {
            if (it.id == config?.groupId) it.copy(useQwdttFallback = false, qwdttOnly = false) else it
        })
    }

    fun setQwdttFallback(groupId: String, enabled: Boolean) {
        if (enabled) require(store.state.value.qwdttConfigs.any { it.groupId == groupId }) { "Сначала добавьте QWDTT-конфиг" }
        store.update { current -> current.copy(groups = current.groups.map {
            if (it.id == groupId) it.copy(useQwdttFallback = enabled, qwdttOnly = enabled && it.qwdttOnly) else it
        }) }
    }

    fun setQwdttOnly(groupId: String, enabled: Boolean) {
        if (enabled) require(store.state.value.qwdttConfigs.any { it.groupId == groupId }) { "Сначала добавьте QWDTT-конфиг" }
        store.update { current -> current.copy(groups = current.groups.map {
            if (it.id == groupId) it.copy(qwdttOnly = enabled, useQwdttFallback = enabled || it.useQwdttFallback) else it
        }) }
    }

    fun forceVless(groupId: String) {
        QwdttEngine.stop()
        store.update { current -> current.copy(groups = current.groups.map {
            if (it.id == groupId) it.copy(qwdttOnly = false) else it
        }) }
        mutableMessage.value = "Переключение на VLESS"
    }

    fun pingQwdtt(id: String) = launchTask {
        val config = store.state.value.qwdttConfigs.firstOrNull { it.id == id } ?: error("QWDTT-конфиг не найден")
        dev.flowroute.app.core.QwdttEngine.ping(getApplication(), config, log = {})
            .onSuccess { delay ->
                store.update { current -> current.copy(qwdttConfigs = current.qwdttConfigs.map {
                    if (it.id == id) it.copy(latencyMs = delay, available = true, checkedAt = System.currentTimeMillis()) else it
                }) }
                mutableMessage.value = "QWDTT: $delay мс"
            }
            .onFailure {
                store.update { current -> current.copy(qwdttConfigs = current.qwdttConfigs.map {
                    if (it.id == id) it.copy(latencyMs = null, available = false, checkedAt = System.currentTimeMillis()) else it
                }) }
            }
            .getOrThrow()
    }

    fun deployQwdtt(groupId: String, request: QwdttDeployRequest) {
        if (QwdttDeployer.state.value.running) return
        viewModelScope.launch {
            try {
                val config = QwdttDeployer.deploy(getApplication(), request, groupId)
                store.update { current ->
                    current.copy(
                        qwdttConfigs = current.qwdttConfigs.filterNot { it.groupId == groupId } + config,
                        groups = current.groups.map { if (it.id == groupId) it.copy(useQwdttFallback = true) else it },
                    )
                }
                mutableMessage.value = "QWDTT установлен и добавлен в группу"
            } catch (error: Throwable) {
                store.recordError("QWDTT deploy", error)
                mutableMessage.value = error.message ?: "Не удалось установить QWDTT"
            }
        }
    }

    fun importSubscription(groupId: String, url: String) = launchTask {
        val group = store.state.value.groups.firstOrNull { it.id == groupId } ?: error("Сначала создайте группу")
        val id = UUID.randomUUID().toString()
        val (subscription, nodes) = importer.import(group.name, url.trim(), "", id, groupId).getOrThrow()
        store.update { it.copy(subscriptions = it.subscriptions + subscription, servers = mergeServerNodes(it.servers, nodes)) }
        mutableMessage.value = "Добавлено серверов: ${nodes.size}"
    }

    fun importConfigs(groupId: String, text: String) = launchTask {
        val group = store.state.value.groups.firstOrNull { it.id == groupId } ?: error("Сначала создайте группу")
        val id = UUID.randomUUID().toString()
        val (_, nodes) = importer.import(group.name, "", text.trim(), id, groupId).getOrThrow()
        store.update { it.copy(servers = mergeServerNodes(it.servers, nodes)) }
        mutableMessage.value = "Добавлено конфигов: ${nodes.size}"
    }

    fun refreshSubscription(id: String, silent: Boolean = false) = launchTask(if (silent) null else "Подписка обновлена") {
        refreshSubscriptionNow(id, throwOnError = !silent)
    }

    private suspend fun refreshSubscriptionNow(id: String, throwOnError: Boolean) {
        val subscription = store.state.value.subscriptions.firstOrNull { it.id == id } ?: return
        try {
            val (updated, nodes) = importer.import(subscription.name, subscription.url, "", subscription.id,
                subscription.groupId.ifBlank { subscription.id }).getOrThrow()
            store.update { current ->
                val withoutOld = current.servers.filterNot { it.subscriptionId == id }
                current.copy(subscriptions = current.subscriptions.map { if (it.id == id) updated else it },
                    servers = mergeServerNodes(withoutOld, nodes))
            }
        } catch (error: Throwable) {
            store.update { current -> current.copy(subscriptions = current.subscriptions.map {
                if (it.id == id) it.copy(lastError = error.message ?: "Ошибка обновления") else it
            }) }
            if (throwOnError) throw error
        }
    }

    fun refreshAllSubscriptions(groupId: String? = null) = launchTask {
        val subscriptions = store.state.value.subscriptions.filter {
            it.url.isNotBlank() && (groupId == null || it.groupId == groupId || (it.groupId.isBlank() && it.id == groupId))
        }
        require(subscriptions.isNotEmpty()) { "Нет подписок для обновления" }
        subscriptions.forEach { refreshSubscriptionNow(it.id, throwOnError = false) }
        mutableMessage.value = "Обновление подписок завершено"
    }

    fun removeSubscription(id: String) = store.update { it.copy(subscriptions = it.subscriptions.filterNot { sub -> sub.id == id },
        servers = it.servers.filterNot { server -> server.subscriptionId == id }) }
    fun toggleSubscription(id: String) = store.update { current -> current.copy(subscriptions = current.subscriptions.map {
        if (it.id == id) it.copy(autoRefresh = !it.autoRefresh) else it }) }

    fun removeGroup(id: String) = store.update { current ->
        val remaining = current.groups.filterNot { it.id == id }
        current.copy(groups = remaining,
            subscriptions = current.subscriptions.filterNot { sub -> sub.groupId == id || sub.id == id },
            servers = current.servers.filterNot { server -> server.groupId == id },
            qwdttConfigs = current.qwdttConfigs.filterNot { it.groupId == id },
            selectedGroupId = if (current.selectedGroupId == id) remaining.firstOrNull()?.id else current.selectedGroupId)
    }
    fun toggleServer(id: String) = store.update { current -> current.copy(servers = current.servers.map {
        if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    fun setServerWeight(id: String, weight: Int) = store.update { current -> current.copy(servers = current.servers.map {
        if (it.id == id) it.copy(weight = weight.coerceIn(1, 10)) else it }) }

    fun pingServers(groupId: String? = null, silent: Boolean = false) = launchTask {
        val servers = store.state.value.servers.filter { groupId == null || it.groupId == groupId || (it.groupId.isBlank() && it.subscriptionId == groupId) }
        require(servers.isNotEmpty()) { "В группе нет серверов" }
        val connected = FlowRouteVpnService.status.value == VpnStatus.CONNECTED
        val allEnabled = store.state.value.servers.filter { it.enabled }
        val target = if (connected) servers.filter { it in allEnabled } else servers
        val delays = if (connected) {
            val allDelays = XrayGateway.queryActiveLatencies(store.state.value)
            target.map { server -> allDelays.getOrNull(allEnabled.indexOf(server)) ?: -1 }
        } else target.chunked(5).flatMap { chunk -> XrayGateway.ping(chunk.map { it.outbound }).getOrThrow() }
        val byId = target.mapIndexed { index, server -> server.id to delays.getOrNull(index) }.toMap()
        val now = System.currentTimeMillis()
        store.update { current -> current.copy(servers = current.servers.map { server ->
            if (!byId.containsKey(server.id)) return@map server
            val delay = byId[server.id]; val alive = delay != null && delay >= 0
            val (failures, available) = FailoverPolicy.nextState(server.failedChecks, alive)
            server.copy(latencyMs = delay, available = available, failedChecks = failures, checkedAt = now)
        }) }
        store.recordHealth(byId.map { (serverId, delay) -> HealthSample(serverId, now, delay ?: -1) })
        if (!silent) mutableMessage.value = "Доступно: ${delays.count { it >= 0 }} из ${target.size}"
    }

    fun sortGroupByLatency(groupId: String) {
        val selected = store.state.value.servers.filter { it.groupId == groupId || (it.groupId.isBlank() && it.subscriptionId == groupId) }
        if (selected.none { it.latencyMs != null && it.latencyMs!! >= 0 }) {
            mutableMessage.value = "Сначала измерьте задержку"
            return
        }
        val sortedIds = selected.sortedWith(compareBy<ServerNode> { it.latencyMs == null || it.latencyMs!! < 0 }.thenBy { it.latencyMs ?: Int.MAX_VALUE }).map { it.id }
        store.update { current -> current.copy(servers = current.servers.sortedBy { sortedIds.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }) }
        mutableMessage.value = "Серверы отсортированы по задержке"
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = store.update { it.copy(settings = transform(it.settings)) }

    fun applyRecommendedRouting(notify: Boolean = true) {
        val context = getApplication<Application>()
        val installed = context.packageManager.getInstalledApplications(0)
            .map { it.packageName to it.loadLabel(context.packageManager).toString() }
        val recommended = RecommendedRouting.settings(installed)
        updateSettings { current -> current.copy(
            regionProfile = recommended.regionProfile,
            appFilterEnabled = recommended.appFilterEnabled,
            appFilterMode = recommended.appFilterMode,
            selectedApps = recommended.selectedApps,
            qwdttRegionProfile = recommended.qwdttRegionProfile,
            qwdttAppFilterEnabled = recommended.qwdttAppFilterEnabled,
            qwdttAppFilterMode = recommended.qwdttAppFilterMode,
            qwdttSelectedApps = recommended.qwdttSelectedApps,
        ) }
        if (notify) mutableMessage.value = "Маршрутизация настроена"
    }

    fun preloadRegionalData() {
        val context = getApplication<Application>()
        val settings = store.state.value.settings
        if ((!settings.regionProfile.needsRegionalData() && !settings.qwdttRegionProfile.needsRegionalData()) ||
            regionalPreloadStarted || mutableRegionalBusy.value || RegionalDataManager.isReady(context)) return
        regionalPreloadStarted = true
        loadRegionalData(force = false, notify = false)
    }

    fun selectRegionProfile(profile: RegionRouteProfile, forceDownload: Boolean = false, channel: RoutingChannel = RoutingChannel.VLESS) {
        val applyProfile = {
            updateSettings { settings -> if (channel == RoutingChannel.QWDTT) settings.copy(qwdttRegionProfile = profile) else settings.copy(regionProfile = profile) }
            mutableMessage.value = "${channel.title}: ${profile.title}"
        }
        if (!profile.needsRegionalData() && !forceDownload) {
            applyProfile()
            return
        }
        if (!forceDownload && RegionalDataManager.isReady(getApplication())) {
            applyProfile()
            return
        }
        if (mutableRegionalBusy.value) {
            mutableMessage.value = "Дождитесь завершения загрузки списков РФ"
            return
        }
        loadRegionalData(force = forceDownload, notify = true, onReady = applyProfile)
    }

    private fun loadRegionalData(force: Boolean, notify: Boolean, onReady: (() -> Unit)? = null) {
        if (mutableRegionalBusy.value) return
        viewModelScope.launch {
            mutableRegionalBusy.value = true
            try {
                val updatedAt = RegionalDataManager.ensure(getApplication(), force)
                updateSettings { it.copy(regionDataUpdatedAt = updatedAt) }
                if (onReady != null) onReady() else if (notify) mutableMessage.value = "Списки РФ обновлены"
            } catch (error: Throwable) {
                store.recordError("Списки РФ", error)
                if (notify) mutableMessage.value = error.message ?: "Не удалось загрузить списки РФ"
            } finally {
                regionalPreloadStarted = false
                mutableRegionalBusy.value = false
            }
        }
    }

    fun applyRussianApps(installedPackages: Collection<String>, mode: AppFilterMode? = null) {
        applyRussianAppMatches(RussianApps.installedMatches(installedPackages), mode, RoutingChannel.VLESS)
    }

    fun applyRussianApps(installedApps: List<Pair<String, String>>, mode: AppFilterMode? = null, channel: RoutingChannel = RoutingChannel.VLESS) {
        applyRussianAppMatches(RussianApps.installedMatches(installedApps), mode, channel)
    }

    private fun applyRussianAppMatches(matches: Set<String>, mode: AppFilterMode?, channel: RoutingChannel) {
        if (matches.isEmpty()) {
            mutableMessage.value = "Приложения РФ не найдены"
            return
        }
        updateChannelApps(channel) { settings, enabled, filterMode, selected ->
            settings.copy(
                appFilterEnabled = if (mode == null) enabled else true,
                appFilterMode = mode ?: filterMode,
                selectedApps = matches,
            )
        }
        mutableMessage.value = "${channel.title}: приложений РФ ${matches.size}"
    }

    fun selectAllApps(installedApps: List<Pair<String, String>>, channel: RoutingChannel = RoutingChannel.VLESS) {
        val selected = installedApps.mapTo(mutableSetOf()) { it.first }
        updateChannelApps(channel) { settings, _, _, _ -> settings.copy(selectedApps = selected) }
        mutableMessage.value = "${channel.title}: выбраны все приложения"
    }

    fun selectNonRussianApps(installedApps: List<Pair<String, String>>, channel: RoutingChannel = RoutingChannel.VLESS) {
        val russian = RussianApps.installedMatches(installedApps)
        val selected = installedApps.mapTo(mutableSetOf()) { it.first } - russian
        updateChannelApps(channel) { settings, _, _, _ -> settings.copy(selectedApps = selected) }
        mutableMessage.value = "${channel.title}: выбрано ${selected.size}"
    }

    fun applyWhitelistApps(installedApps: List<Pair<String, String>>, channel: RoutingChannel = RoutingChannel.VLESS) {
        val matches = WhitelistPreset.installedMatches(installedApps)
        if (matches.isEmpty()) {
            mutableMessage.value = "Приложения из белого списка не найдены"
            return
        }
        updateChannelApps(channel) { settings, _, _, _ -> settings.copy(selectedApps = matches) }
        mutableMessage.value = "${channel.title}: выбрано приложений ${matches.size}"
    }

    fun applyBlockedApps(installedApps: List<Pair<String, String>>, channel: RoutingChannel = RoutingChannel.VLESS) {
        val matches = BlockedApps.installedMatches(installedApps)
        if (matches.isEmpty()) {
            mutableMessage.value = "Браузеры и заблокированные приложения не найдены"
            return
        }
        updateChannelApps(channel) { settings, _, _, _ -> settings.copy(selectedApps = matches) }
        mutableMessage.value = "${channel.title}: выбрано приложений ${matches.size}"
    }

    fun clearSelectedApps(channel: RoutingChannel = RoutingChannel.VLESS) {
        updateChannelApps(channel) { settings, _, _, _ -> settings.copy(selectedApps = emptySet()) }
        mutableMessage.value = "Выделение снято"
    }

    fun updateChannelApps(channel: RoutingChannel, transform: (AppSettings, Boolean, AppFilterMode, Set<String>) -> AppSettings) {
        updateSettings { settings ->
            val updated = transform(settings, settings.appFilterEnabled(channel), settings.appFilterMode(channel), settings.selectedApps(channel))
            if (channel == RoutingChannel.QWDTT) settings.copy(
                qwdttAppFilterEnabled = updated.appFilterEnabled,
                qwdttAppFilterMode = updated.appFilterMode,
                qwdttSelectedApps = updated.selectedApps,
            ) else updated
        }
    }

    fun addWhitelistRule() {
        if (store.state.value.routes.any { it.name == "Белые списки" }) {
            mutableMessage.value = "Правило «Белые списки» уже добавлено"
            return
        }
        addRule("Белые списки", WhitelistPreset.domains.joinToString(","), false, RouteAction.DIRECT, null, null)
        mutableMessage.value = "Белые списки будут открываться напрямую"
    }

    fun addAdBlockRule() = addRule("Реклама", AD_BLOCK_DOMAINS, false, RouteAction.BLOCK, null, null)

    fun addRule(name: String, values: String, isIp: Boolean, action: RouteAction, serverId: String?, groupId: String?) {
        val entries = values.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.isNotEmpty()) { "Добавьте домен или IP" }
        require(action != RouteAction.SERVER || serverId != null) { "Выберите сервер" }
        store.update { it.copy(routes = it.routes + RouteRule(UUID.randomUUID().toString(), name.ifBlank { entries.first() },
            entries, isIp, action, serverId, groupId = groupId)) }
    }
    fun toggleRule(id: String) = store.update { current -> current.copy(routes = current.routes.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    fun removeRule(id: String) = store.update { it.copy(routes = it.routes.filterNot { rule -> rule.id == id }) }
    fun moveRule(id: String, delta: Int) = store.update { current ->
        val index = current.routes.indexOfFirst { it.id == id }; val target = index + delta
        if (index < 0 || target !in current.routes.indices) return@update current
        current.copy(routes = current.routes.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun checkExternalIps() {
        if (mutableCheckingIps.value) return
        viewModelScope.launch {
            mutableCheckingIps.value = true
            try {
                mutableExternalIps.value = XrayGateway.queryExternalIps(store.state.value)
            } catch (error: Throwable) {
                store.recordError("Внешние IP", error)
                mutableMessage.value = error.message ?: "Не удалось проверить внешние IP"
            } finally {
                mutableCheckingIps.value = false
            }
        }
    }

    fun diagnosticText(): String {
        val current = store.state.value
        return buildString {
            appendLine("FlowRoute ${BuildConfig.VERSION_NAME} · ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("VPN: ${FlowRouteVpnService.status.value}; стратегия: ${current.settings.strategy.title}")
            appendLine("Sticky session (приложение): ${current.settings.stickySessions}; failover: ${current.settings.autoFailover}")
            current.servers.forEach { appendLine("${it.name}: enabled=${it.enabled}, alive=${it.available}, latency=${it.latencyMs}, failures=${it.failedChecks}") }
            FlowRouteVpnService.lastError.value.takeIf { it.isNotBlank() }?.let { appendLine("Ошибка: $it") }
            FlowRouteVpnService.diagnostics.value.forEach(::appendLine)
        }
    }

    fun clearDnsCache() {
        XrayGateway.resetDnsCache()
        if (FlowRouteVpnService.status.value == VpnStatus.CONNECTED) {
            FlowRouteVpnService.clearDnsCache(getApplication())
            mutableMessage.value = "DNS-кэш очищается, маршруты перезапускаются"
        } else mutableMessage.value = "DNS-кэш очищен"
    }

    fun exportConfiguration(uri: Uri) = launchTask("Резервная копия сохранена") {
        val resolver = getApplication<Application>().contentResolver
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(store.exportConfiguration()) }
            ?: error("Не удалось открыть файл для сохранения")
    }

    fun importConfiguration(uri: Uri) = launchTask("Настройки восстановлены") {
        val resolver = getApplication<Application>().contentResolver
        val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Не удалось открыть резервную копию")
        FlowRouteVpnService.stop(getApplication())
        store.importConfiguration(raw)
    }

    fun consumeMessage() { mutableMessage.value = null }
    fun clearErrorLogs() = store.clearErrors()

    private fun launchTask(successMessage: String? = null, block: suspend () -> Unit) {
        if (mutableBusy.value) {
            mutableMessage.value = "Дождитесь завершения текущей операции"
            return
        }
        viewModelScope.launch {
            mutableBusy.value = true
            try { block(); successMessage?.let { mutableMessage.value = it } }
            catch (error: Throwable) {
                store.recordError("Операция", error)
                mutableMessage.value = error.message ?: "Операция не выполнена"
            }
            finally { mutableBusy.value = false }
        }
    }

    private companion object {
        val AD_BLOCK_DOMAINS = listOf(
            "domain:doubleclick.net", "domain:googlesyndication.com", "domain:googleadservices.com",
            "domain:google-analytics.com", "domain:googletagservices.com", "domain:admob.com",
            "domain:adservice.google.com", "domain:app-measurement.com", "domain:appsflyer.com",
            "domain:adjust.com", "domain:adcolony.com", "domain:applovin.com", "domain:unityads.unity3d.com",
        ).joinToString(",")
    }
}

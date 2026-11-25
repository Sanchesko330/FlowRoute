package dev.flowroute.app

import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flowroute.app.core.FlowRouteVpnService
import dev.flowroute.app.data.*

private val Ink = Color(0xFF0B0F14)
private val Panel = Color(0xFF141A22)
private val PanelLight = Color(0xFF1B232E)
private val Mint = Color(0xFF77E6B6)
private val SecondaryText = Color(0xFF94A1B2)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) FlowRouteVpnService.start(this)
    }
    private val exportConfiguration = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(viewModel::exportConfiguration)
    }
    private val importConfiguration = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlowRouteTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val status by FlowRouteVpnService.status.collectAsStateWithLifecycle()
                val traffic by FlowRouteVpnService.traffic.collectAsStateWithLifecycle()
                val context = LocalContext.current
                FlowRouteAppContent(
                    state = state,
                    status = status,
                    traffic = traffic,
                    viewModel = viewModel,
                    onToggleVpn = {
                        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
                            FlowRouteVpnService.stop(context)
                        } else {
                            VpnService.prepare(context)?.let(vpnPermission::launch)
                                ?: FlowRouteVpnService.start(context)
                        }
                    },
                    onExportConfiguration = { exportConfiguration.launch("FlowRoute-backup.json") },
                    onImportConfiguration = { importConfiguration.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }
        }
    }
}

@Composable
private fun FlowRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Ink,
            background = Ink,
            surface = Panel,
            surfaceVariant = PanelLight,
            onSurface = Color(0xFFF3F6F8),
            onSurfaceVariant = SecondaryText,
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowRouteAppContent(
    state: AppState,
    status: VpnStatus,
    traffic: Map<String, TrafficCounter>,
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
    onExportConfiguration: () -> Unit,
    onImportConfiguration: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                val items = listOf(
                    Triple("Главная", Icons.Rounded.Home, "Подключение"),
                    Triple("Группы", Icons.Rounded.Folder, "Группы серверов"),
                    Triple("Правила", Icons.Rounded.AltRoute, "Маршруты"),
                    Triple("Приложения", Icons.Rounded.Apps, "Приложения"),
                    Triple("Обзор", Icons.Rounded.MonitorHeart, "Диагностика"),
                )
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.second, item.third) },
                        label = { Text(item.first, maxLines = 1, fontSize = 9.sp, letterSpacing = (-0.2).sp, softWrap = false) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = Mint,
                            indicatorColor = Mint,
                            unselectedIconColor = SecondaryText,
                            unselectedTextColor = SecondaryText,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(360)) { it * direction / 5 } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(300)) { -it * direction / 7 } + fadeOut(tween(200)))
            },
            label = "screen",
        ) { page ->
            when (page) {
                0 -> HomeScreen(state, status, traffic, viewModel, onToggleVpn, onExportConfiguration, onImportConfiguration)
                1 -> GroupsScreen(state, viewModel)
                2 -> RoutesScreen(state, viewModel)
                3 -> AppsScreen(state, viewModel)
                else -> DiagnosticsScreen(state, status, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: AppState,
    status: VpnStatus,
    traffic: Map<String, TrafficCounter>,
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
    onExportConfiguration: () -> Unit,
    onImportConfiguration: () -> Unit,
) {
    val active = state.activeServers().count { it.enabled }
    val activeGroup = state.activeGroup()
    val qwdttState by dev.flowroute.app.core.QwdttEngine.state.collectAsStateWithLifecycle()
    val lastError by FlowRouteVpnService.lastError.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    var showDeploy by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { BrandHeader() }
        if (state.groups.isNotEmpty()) item {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = activeGroup?.name ?: "Выберите группу", onValueChange = {}, readOnly = true,
                    label = { Text("Активная группа") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) }, onClick = {
                        viewModel.selectGroup(group.id); expanded = false
                    }) }
                }
            }
        }
        if (lastError.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1D24))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ошибка подключения", color = Color(0xFFFFB4AB), fontWeight = FontWeight.Bold)
                    Text(lastError, color = Color(0xFFFFDAD6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            ConnectionHero(status, active, state.routes.count { it.enabled }, onToggleVpn,
                canConnect = active > 0 ||
                    (activeGroup?.qwdttOnly != true && state.settings.regionProfile == RegionRouteProfile.ALL_DIRECT) ||
                    (activeGroup?.qwdttOnly == true && state.qwdttConfigs.any { it.groupId == activeGroup.id }),
                qwdttAvailable = activeGroup != null && state.qwdttConfigs.any { it.groupId == activeGroup.id },
                qwdttActive = qwdttState.status == QwdttStatus.CONNECTED || activeGroup?.qwdttOnly == true,
                onSwitchChannel = {
                    val group = activeGroup ?: return@ConnectionHero
                    if (qwdttState.status == QwdttStatus.CONNECTED || group.qwdttOnly) viewModel.forceVless(group.id)
                    else viewModel.setQwdttOnly(group.id, true)
                })
        }
        if (activeGroup?.useQwdttFallback == true || qwdttState.status != QwdttStatus.IDLE) item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = if (qwdttState.status == QwdttStatus.ERROR) MaterialTheme.colorScheme.error else Mint)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Резерв QWDTT", fontWeight = FontWeight.SemiBold)
                        Text(when (qwdttState.status) {
                            QwdttStatus.IDLE -> "Включится при недоступности всех VLESS"
                            QwdttStatus.CHECKING_DNS -> "Проверка DNS"
                            QwdttStatus.CHECKING_VK -> "VK доступен"
                            QwdttStatus.STARTING -> qwdttState.detail
                            QwdttStatus.CONNECTED -> "Канал активен"
                            QwdttStatus.ERROR -> qwdttState.detail
                        }, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (qwdttState.status == QwdttStatus.CHECKING_DNS || qwdttState.status == QwdttStatus.STARTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Серверы", active.toString(), Icons.Rounded.Dns, Modifier.weight(1f))
                MetricCard("Стратегия", state.settings.strategy.title, Icons.Rounded.Shuffle, Modifier.weight(1f))
            }
        }
        item { TrafficCard(state.activeServers(), traffic, status == VpnStatus.CONNECTED) }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Балансировка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(value = state.settings.strategy.title, onValueChange = {}, readOnly = true,
                            label = { Text("Стратегия") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            BalanceStrategy.entries.forEach { strategy -> DropdownMenuItem(text = { Text(strategy.title) }, onClick = {
                                viewModel.updateSettings { it.copy(strategy = strategy) }; expanded = false
                            }) }
                        }
                    }
                    SettingSwitch("Автоматический обход сбоев", "Исключать VLESS и включать резерв QWDTT", state.settings.autoFailover) { enabled ->
                        viewModel.updateSettings { it.copy(autoFailover = enabled) }
                    }
                    SettingSwitch("Sticky session", "Один сервер для каждого приложения", state.settings.stickySessions) { enabled ->
                        viewModel.updateSettings { it.copy(stickySessions = enabled) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("DNS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var dnsExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = dnsExpanded, onExpandedChange = { dnsExpanded = it }) {
                        OutlinedTextField(value = state.settings.dnsProvider.title, onValueChange = {}, readOnly = true,
                            label = { Text("DNS-сервер") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dnsExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = dnsExpanded, onDismissRequest = { dnsExpanded = false }) {
                            DnsProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.title) }, onClick = {
                                viewModel.updateSettings { it.copy(dnsProvider = provider) }; dnsExpanded = false
                            }) }
                        }
                    }
                    OutlinedButton(onClick = { viewModel.clearDnsCache() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CleaningServices, null); Spacer(Modifier.width(8.dp)); Text("Очистить DNS-кэш")
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    OutlinedButton(onClick = { showDeploy = true }, enabled = activeGroup != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Установить сервер QWDTT")
                    }
                    Text("Применится при следующем подключении", color = SecondaryText, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("Резервная копия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onExportConfiguration, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Upload, null); Spacer(Modifier.width(6.dp)); Text("Экспорт")
                        }
                        OutlinedButton(onClick = onImportConfiguration, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Импорт")
                        }
                    }
                    Text("Файл содержит серверы, подписки, пароли QWDTT и настройки. Храните его в защищённом месте.", color = SecondaryText, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    OutlinedButton(onClick = { viewModel.checkUpdates() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("Проверить обновления")
                    }
                    Text("Версия ${BuildConfig.VERSION_NAME} · GitHub Releases", color = SecondaryText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    UpdateDialog(update, viewModel::installUpdate, viewModel::dismissUpdate)
    if (showDeploy && activeGroup != null) QwdttDeployDialog(activeGroup.id, viewModel) { showDeploy = false }
}

@Composable
private fun UpdateDialog(state: AppUpdateUiState, onInstall: () -> Unit, onDismiss: () -> Unit) {
    if (state.stage == UpdateStage.CHECKING) {
        AlertDialog(onDismissRequest = {}, title = { Text("Обновления") }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text("Проверяем GitHub Releases…")
            }
        }, confirmButton = {})
        return
    }
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = { if (state.stage != UpdateStage.DOWNLOADING) onDismiss() },
        title = { Text("FlowRoute ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.stage == UpdateStage.READY) "APK загружен. Подтвердите установку в системном окне." else "Доступна новая версия. Текущая: ${BuildConfig.VERSION_NAME}")
                if (release.notes.isNotBlank()) Text(release.notes, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = state.stage != UpdateStage.DOWNLOADING) {
                Text(if (state.stage == UpdateStage.READY) "Установить" else "Скачать и установить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = state.stage != UpdateStage.DOWNLOADING) { Text("Позже") } },
    )
}

@Composable
private fun RoutesScreen(state: AppState, viewModel: MainViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(RoutingChannel.VLESS) }
    val profile = state.settings.regionProfile(channel)
    val regionalBusy by viewModel.regionalBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.preloadRegionalData() }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader("Маршрутизация") }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.applyRecommendedRouting() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(8.dp)); Text("Настроить автоматически")
                    }
                    Text("VLESS: заблокированные приложения и ресурсы. QWDTT: всё, кроме приложений из белого списка.", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingChannel.entries.forEach { item -> FilterChip(selected = channel == item, onClick = { channel = item },
                label = { Text(item.title) }, modifier = Modifier.weight(1f)) }
        } }
        item { Text("Готовые пресеты · ${channel.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(RegionRouteProfile.entries) { preset ->
            Card(onClick = { viewModel.selectRegionProfile(preset, channel = channel) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = if (profile == preset) PanelLight else Panel)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.title, fontWeight = FontWeight.SemiBold)
                        Text(when (preset) {
                            RegionRouteProfile.ALL_VPN -> "Все сайты и сервисы используют ${channel.title}"
                            RegionRouteProfile.BLOCKED_VPN -> "Заблокированные — через ${channel.title}, остальные — напрямую"
                            RegionRouteProfile.RU_DIRECT -> "Российские ресурсы — напрямую, остальные — через ${channel.title}"
                            RegionRouteProfile.WHITELIST_DIRECT -> "Белый список — напрямую, остальные — через ${channel.title}"
                            RegionRouteProfile.WHITELIST_VPN -> "Белый список — через ${channel.title}, остальные — напрямую"
                            RegionRouteProfile.ALL_DIRECT -> "VPN включён, но весь трафик идёт без него"
                        }, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(selected = profile == preset, onClick = null)
                }
            }
        }
        if (profile.needsRegionalData()) item {
            OutlinedButton(onClick = { viewModel.selectRegionProfile(profile, forceDownload = true, channel = channel) },
                enabled = !regionalBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (regionalBusy) "Загрузка…" else "Обновить списки РФ")
            }
        }
        item { Text("Дополнительные правила", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить своё правило") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { viewModel.addRule("Локальные сервисы", "192.168.0.0/16,10.0.0.0/8", true, RouteAction.DIRECT, null, null) }, label = { Text("LAN напрямую") })
            AssistChip(onClick = { viewModel.addAdBlockRule() }, label = { Text("Блок рекламы") })
        } }
        item { Text("Свои правила применяются раньше пресета и общие для обоих каналов. Белый список: Госуслуги, VK, MAX, Яндекс, банки, маркетплейсы и другие отечественные сервисы.",
            color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
        if (state.routes.isEmpty()) item { EmptyState(Icons.Rounded.AltRoute, "Нет правил", "Добавьте домен, IP или CIDR") }
        items(state.routes, key = { it.id }) { rule ->
            val index = state.routes.indexOf(rule)
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        IconButton(onClick = { viewModel.moveRule(rule.id, -1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Выше") }
                        IconButton(onClick = { viewModel.moveRule(rule.id, 1) }, enabled = index < state.routes.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Ниже") }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${rule.name}", fontWeight = FontWeight.SemiBold)
                        Text(rule.values.joinToString(), color = SecondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val target = if (rule.action == RouteAction.BALANCE) state.groups.firstOrNull { it.id == rule.groupId }?.name else null
                        Text(rule.action.title + (target?.let { ": $it" } ?: ""), color = Mint, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRule(rule.id) })
                    IconButton(onClick = { viewModel.removeRule(rule.id) }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                }
            }
        }
    }
    if (showAdd) RuleDialog(state.servers.filter { it.enabled }, state.groups, onDismiss = { showAdd = false }, onAdd = { name, values, ip, action, serverId, groupId ->
        viewModel.addRule(name, values, ip, action, serverId, groupId); showAdd = false
    })
}

@Composable
private fun AppsScreen(state: AppState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf(RoutingChannel.VLESS) }
    val selectedApps = state.settings.selectedApps(channel)
    val filterEnabled = state.settings.appFilterEnabled(channel)
    val filterMode = state.settings.appFilterMode(channel)
    val apps by produceState(initialValue = emptyList<Pair<String, String>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName }
                .map { it.packageName to it.loadLabel(context.packageManager).toString() }
                .sortedBy { it.second.lowercase() }
        }
    }
    val filteredApps = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) apps else apps.filter { it.first.lowercase().contains(needle) || it.second.lowercase().contains(needle) }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BrandHeader("Приложения") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingChannel.entries.forEach { item -> FilterChip(selected = channel == item, onClick = { channel = item },
                label = { Text(item.title) }, modifier = Modifier.weight(1f)) }
        } }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Поиск") }, placeholder = { Text("Название или пакет") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить") } })
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Быстрый выбор · ${channel.title}", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { viewModel.selectAllApps(apps, channel) }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Выбрать все")
                    }
                    OutlinedButton(onClick = { viewModel.applyRussianApps(apps, channel = channel) }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Выбрать РУ")
                    }
                    OutlinedButton(onClick = { viewModel.applyWhitelistApps(apps, channel) }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Выбрать из белого списка")
                    }
                    OutlinedButton(onClick = { viewModel.selectNonRussianApps(apps, channel) }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Выбрать всё, что не РУ")
                    }
                    OutlinedButton(onClick = { viewModel.applyBlockedApps(apps, channel) }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Заблокированные в РФ")
                    }
                    TextButton(onClick = { viewModel.clearSelectedApps(channel) }, enabled = selectedApps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Убрать все")
                    }
                    Text("Выбрано: ${selectedApps.size}. Каждая кнопка заменяет текущий выбор.", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сплит-туннелинг ${channel.title}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = filterEnabled, onCheckedChange = { enabled -> viewModel.updateChannelApps(channel) { settings, _, _, _ -> settings.copy(appFilterEnabled = enabled) } })
                    }
                    if (filterEnabled) {
                        FilterChip(selected = filterMode == AppFilterMode.EXCLUDE_SELECTED,
                            onClick = { viewModel.updateChannelApps(channel) { settings, _, _, _ -> settings.copy(appFilterMode = AppFilterMode.EXCLUDE_SELECTED) } },
                            label = { Text("Выбранные — напрямую") }, modifier = Modifier.fillMaxWidth())
                        FilterChip(selected = filterMode == AppFilterMode.INCLUDE_SELECTED,
                            onClick = { viewModel.updateChannelApps(channel) { settings, _, _, _ -> settings.copy(appFilterMode = AppFilterMode.INCLUDE_SELECTED) } },
                            label = { Text("Только выбранные — через VPN") }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(when {
                        !filterEnabled -> "Фильтр выключен: все приложения следуют маршрутизации ${channel.title}."
                        filterMode == AppFilterMode.EXCLUDE_SELECTED -> "Выбранные приложения обходят VPN. Все остальные идут через ${channel.title}."
                        else -> "Только выбранные приложения идут через ${channel.title}. Все остальные обходят VPN."
                    }, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    Text("Изменения применяются сразу: VPN-интерфейс кратко пересоздаётся.", color = SecondaryText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (apps.isNotEmpty() && filteredApps.isEmpty()) item {
            EmptyState(Icons.Rounded.SearchOff, "Ничего не найдено", "Измените поисковый запрос")
        }
        items(filteredApps, key = { it.first }) { app ->
            val selected = app.first in selectedApps
            Card(colors = CardDefaults.cardColors(containerColor = if (selected) PanelLight else Panel)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.first, color = SecondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = selected, onCheckedChange = {
                        viewModel.updateChannelApps(channel) { settings, _, _, current -> settings.copy(selectedApps = if (selected) current - app.first else current + app.first) }
                    })
                }
            }
        }
    }
}

@Composable
private fun RuleDialog(servers: List<ServerNode>, groups: List<ServerGroup>, onDismiss: () -> Unit, onAdd: (String, String, Boolean, RouteAction, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var values by remember { mutableStateOf("") }
    var isIp by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(RouteAction.DIRECT) }
    var serverId by remember { mutableStateOf<String?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое правило") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(values, { values = it }, label = { Text(if (isIp) "IP или CIDR" else "Домены") }, placeholder = { Text(if (isIp) "10.0.0.0/8, 1.1.1.1" else "example.com, domain:bank.ru") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Правило по IP", Modifier.weight(1f)); Switch(isIp, { isIp = it }) }
                RouteAction.entries.forEach { item ->
                    FilterChip(selected = action == item, onClick = { action = item }, label = { Text(item.title) }, modifier = Modifier.fillMaxWidth())
                }
                if (action == RouteAction.SERVER) servers.forEach { server ->
                    FilterChip(selected = serverId == server.id, onClick = { serverId = server.id }, label = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth())
                }
                if (action == RouteAction.BALANCE && groups.isNotEmpty()) {
                    FilterChip(selected = groupId == null, onClick = { groupId = null }, label = { Text("Все группы") }, modifier = Modifier.fillMaxWidth())
                    groups.forEach { group -> FilterChip(selected = groupId == group.id, onClick = { groupId = group.id }, label = { Text(group.name) }, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, values, isIp, action, serverId, groupId) }, enabled = values.isNotBlank() && (action != RouteAction.SERVER || serverId != null)) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun BrandHeader(title: String? = null) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("FLOWROUTE", color = Mint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing * 2)
        if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Mint, modifier = Modifier.size(21.dp))
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 14.dp))
            Text(label, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = SecondaryText, modifier = Modifier.size(42.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text(text, color = SecondaryText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

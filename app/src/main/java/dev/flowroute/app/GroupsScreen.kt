package dev.flowroute.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flowroute.app.core.QwdttEngine
import dev.flowroute.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class GroupDialog { CREATE, CONFIG, SUBSCRIPTION, QWDDT }

@Composable
internal fun GroupsScreen(state: AppState, viewModel: MainViewModel) {
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }
    var targetGroup by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Column(Modifier.padding(vertical = 10.dp)) {
            Text("FLOWROUTE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text("Группы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        } }
        item { Button(onClick = { dialog = GroupDialog.CREATE }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Создать группу") } }
        if (state.groups.isEmpty()) item { GroupEmptyState() }
        items(state.groups, key = { it.id }) { group ->
            val nodes = state.servers.filter { it.groupId == group.id || (it.groupId.isBlank() && it.subscriptionId == group.id) }
            val subscriptions = state.subscriptions.filter { it.groupId == group.id || (it.groupId.isBlank() && it.id == group.id) }
            val qwdtt = state.qwdttConfigs.firstOrNull { it.groupId == group.id }
            val isExpanded = expanded[group.id] == true
            val isSelected = state.activeGroup()?.id == group.id
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.animateContentSize()) {
                Column {
                    GroupHeader(group, nodes, subscriptions.size, isExpanded, isSelected, busy,
                        onExpand = { expanded[group.id] = !isExpanded },
                        onRefresh = if (subscriptions.isEmpty()) null else ({ viewModel.refreshAllSubscriptions(group.id) }))
                    AnimatedVisibility(isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!isSelected) OutlinedButton(onClick = { viewModel.selectGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.RadioButtonUnchecked, null); Spacer(Modifier.width(8.dp)); Text("Использовать эту группу")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallGroupAction("Конфиг", Icons.Rounded.Add, Modifier.weight(1f)) { targetGroup = group.id; dialog = GroupDialog.CONFIG }
                                SmallGroupAction("Подписка", Icons.Rounded.CloudDownload, Modifier.weight(1f)) { targetGroup = group.id; dialog = GroupDialog.SUBSCRIPTION }
                            }
                            OutlinedButton(onClick = { targetGroup = group.id; dialog = GroupDialog.QWDDT }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Shield, null); Spacer(Modifier.width(8.dp)); Text(if (qwdtt == null) "Добавить QWDTT" else "Заменить QWDTT")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallGroupAction("Проверить", Icons.Rounded.Speed, Modifier.weight(1f), enabled = !busy && nodes.isNotEmpty()) { viewModel.pingServers(group.id) }
                                SmallGroupAction("По задержке", Icons.Rounded.Sort, Modifier.weight(1f), enabled = nodes.isNotEmpty()) { viewModel.sortGroupByLatency(group.id) }
                            }
                            if (qwdtt != null) {
                                QwdttCard(qwdtt, group.useQwdttFallback, group.qwdttOnly, busy,
                                    onToggle = { viewModel.setQwdttFallback(group.id, it) },
                                    onOnly = { viewModel.setQwdttOnly(group.id, it) },
                                    onPing = { viewModel.pingQwdtt(qwdtt.id) },
                                    onForceVless = { viewModel.forceVless(group.id) },
                                    onRemove = { viewModel.removeQwdtt(qwdtt.id) })
                            } else {
                                Text("Резерв VK не настроен", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            subscriptions.forEach { subscription ->
                                SubscriptionCard(subscription, nodes.filter { it.subscriptionId == subscription.id }, busy,
                                    state.settings.strategy == BalanceStrategy.LEAST_LOAD, viewModel)
                            }
                            val standaloneNodes = nodes.filter { node -> subscriptions.none { it.id == node.subscriptionId } }
                            if (standaloneNodes.isNotEmpty()) {
                                Text("Отдельные конфиги", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                            }
                            if (nodes.isEmpty()) Text("Нет VLESS-серверов", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                            standaloneNodes.forEach { server -> GroupServerCard(server, state.settings.strategy == BalanceStrategy.LEAST_LOAD,
                                onToggle = { viewModel.toggleServer(server.id) }, onWeight = { viewModel.setServerWeight(server.id, it) }) }
                            TextButton(onClick = { viewModel.removeGroup(group.id) }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Удалить группу")
                            }
                        }
                    }
                }
            }
        }
    }
    when (dialog) {
        GroupDialog.CREATE -> CreateGroupDialog(onDismiss = { dialog = null }, onCreate = { viewModel.createGroup(it); dialog = null })
        GroupDialog.CONFIG -> ConfigDialog(onDismiss = { dialog = null }, onImport = { text -> viewModel.importConfigs(targetGroup, text); dialog = null })
        GroupDialog.SUBSCRIPTION -> SubscriptionDialog(onDismiss = { dialog = null }, onImport = { url -> viewModel.importSubscription(targetGroup, url); dialog = null })
        GroupDialog.QWDDT -> QwdttDialog(onDismiss = { dialog = null }, onImport = { text -> viewModel.importQwdtt(targetGroup, text); dialog = null })
        null -> Unit
    }
}

@Composable
private fun GroupHeader(group: ServerGroup, nodes: List<ServerNode>, subscriptionCount: Int, expanded: Boolean, selected: Boolean, busy: Boolean,
    onExpand: () -> Unit, onRefresh: (() -> Unit)?) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val alive = nodes.count { it.available != false && it.enabled }
            Text("$alive активных · ${nodes.size} конфигов · $subscriptionCount подписок", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (onRefresh != null) IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Обновить подписки группы", tint = MaterialTheme.colorScheme.primary) }
        Icon(Icons.Rounded.ExpandMore, "Раскрыть", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QwdttCard(
    config: QwdttConfig,
    enabled: Boolean,
    only: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onOnly: (Boolean) -> Unit,
    onPing: () -> Unit,
    onForceVless: () -> Unit,
    onRemove: () -> Unit,
) {
    val runtime by QwdttEngine.state.collectAsStateWithLifecycle()
    val active = runtime.configId == config.id && runtime.status != QwdttStatus.IDLE
    val connected = active && runtime.status == QwdttStatus.CONNECTED
    val statusColor = when {
        connected -> MaterialTheme.colorScheme.primary
        runtime.status == QwdttStatus.ERROR && active -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(config.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(config.peer, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Удалить QWDTT") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                Text(if (connected) "Подключено через QWDTT" else if (active) runtime.detail else "QWDTT не подключён",
                    color = statusColor, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
            }
            val checked = if (config.checkedAt > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(config.checkedAt)) else null
            Text(when {
                config.available == false -> "Недоступен${checked?.let { " · $it" } ?: ""}"
                config.latencyMs != null -> "${config.latencyMs} мс${checked?.let { " · $it" } ?: ""}"
                else -> "Задержка не измерена"
            }, color = if (config.available == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 17.dp, top = 2.dp))
            SettingSwitch("Только QWDTT", "Использовать VK без VLESS", only, onOnly)
            if (!only) SettingSwitch("Резервный канал VK", "Включать, если все VLESS недоступны", enabled, onToggle)
            OutlinedButton(onClick = onPing, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Speed, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Проверка…" else "Проверить задержку")
            }
            Button(onClick = { if (connected || only) onForceVless() else onOnly(true) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.SwapHoriz, null)
                Spacer(Modifier.width(8.dp))
                Text(if (connected || only) "Переключиться на VLESS" else "Переключиться на QWDTT")
            }
        }
    }
}

@Composable
private fun SmallGroupAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(text, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SubscriptionCard(subscription: Subscription, nodes: List<ServerNode>, busy: Boolean, showWeight: Boolean, viewModel: MainViewModel) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(subscription.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val updated = if (subscription.updatedAt > 0) SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(subscription.updatedAt)) else "не обновлялась"
                    Text("$updated · конфигов: ${nodes.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.refreshSubscription(subscription.id) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Обновить") }
                IconButton(onClick = { viewModel.removeSubscription(subscription.id) }) { Icon(Icons.Rounded.Close, "Удалить") }
            }
            if (subscription.lastError.isNotBlank()) Text(subscription.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            SettingSwitch("Автообновление", "Проверять изменения подписки", subscription.autoRefresh) { viewModel.toggleSubscription(subscription.id) }
            if (nodes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                nodes.forEach { server -> GroupServerCard(server, showWeight,
                    onToggle = { viewModel.toggleServer(server.id) }, onWeight = { viewModel.setServerWeight(server.id, it) }) }
            }
        }
    }
}

@Composable
private fun GroupServerCard(server: ServerNode, showWeight: Boolean, onToggle: () -> Unit, onWeight: (Int) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val healthy = server.enabled && server.available != false
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${server.host}${if (server.port > 0) ":${server.port}" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val latency = server.latencyMs
                    Text(when {
                        server.available == false -> "Недоступен · сбоев: ${server.failedChecks}"
                        latency == null -> "Задержка не измерена"
                        latency >= 0 -> "$latency мс"
                        else -> "Недоступен"
                    }, color = if (healthy && latency != null && latency >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = server.enabled, onCheckedChange = { onToggle() })
            }
            if (showWeight) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Вес для leastLoad", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onWeight(server.weight - 1) }, enabled = server.weight > 1) { Icon(Icons.Rounded.Remove, "Меньше") }
                Text(server.weight.toString(), fontWeight = FontWeight.Bold)
                IconButton(onClick = { onWeight(server.weight + 1) }, enabled = server.weight < 10) { Icon(Icons.Rounded.Add, "Больше") }
            }
        }
    }
}

@Composable private fun CreateGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новая группа") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Название группы") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("Создать") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable private fun ConfigDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить конфиги") },
        text = { Column {
            OutlinedTextField(text, { text = it }, label = { Text("VLESS-ссылки") }, minLines = 4, maxLines = 7)
            OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Сканировать QR")
            }
        } },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Добавить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
    if (scanning) QrScannerDialog(onDismiss = { scanning = false }, onResult = { text = it; scanning = false })
}

@Composable private fun SubscriptionDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить подписку") },
        text = { Column {
            OutlinedTextField(url, { url = it }, label = { Text("HTTP(S)-ссылка") }, singleLine = true)
            OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Сканировать QR")
            }
        } },
        confirmButton = { TextButton(onClick = { onImport(url) }, enabled = url.isNotBlank()) { Text("Добавить") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
    if (scanning) QrScannerDialog(target = QrScanTarget.SUBSCRIPTION, onDismiss = { scanning = false }, onResult = { url = it; scanning = false })
}

@Composable private fun QwdttDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Резерв QWDTT") },
        text = { Column {
            OutlinedTextField(text, { text = it }, label = { Text("qwdtt:// или wdtt://") }, minLines = 3, maxLines = 6)
            OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Сканировать QR")
            }
        } },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
    if (scanning) QrScannerDialog(target = QrScanTarget.QWDDT, onDismiss = { scanning = false }, onResult = { text = it; scanning = false })
}

@Composable private fun GroupEmptyState() {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(42.dp))
        Text("Нет групп", modifier = Modifier.padding(top = 16.dp))
    }
}

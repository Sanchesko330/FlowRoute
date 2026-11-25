package dev.flowroute.app

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flowroute.app.core.FlowRouteVpnService
import dev.flowroute.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class HealthRange(val title: String, val durationMillis: Long) {
    HOUR("1 час", 3_600_000L),
    DAY("24 часа", 86_400_000L),
    WEEK("7 дней", 604_800_000L),
}

@Composable
internal fun DiagnosticsScreen(state: AppState, status: VpnStatus, viewModel: MainViewModel) {
    val samples by FlowRouteVpnService.samples.collectAsStateWithLifecycle()
    val speeds by FlowRouteVpnService.speeds.collectAsStateWithLifecycle()
    val sessionLogs by FlowRouteVpnService.diagnostics.collectAsStateWithLifecycle()
    val ips by viewModel.externalIps.collectAsStateWithLifecycle()
    val checking by viewModel.checkingIps.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var logsOpen by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(HealthRange.DAY) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(range) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000L)
        }
    }
    val rangeStart = now - range.durationMillis
    val monitoredServers = state.activeServers()

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.padding(vertical = 10.dp)) {
            Text("FLOWROUTE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Обзор", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        } }
        item { DiagnosticCard { Column(Modifier.padding(16.dp)) {
            Text("Скорость сейчас", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val latest = samples.lastOrNull()
            Text("↑ ${speed(latest?.uploadBytesPerSecond ?: 0)}   ↓ ${speed(latest?.downloadBytesPerSecond ?: 0)}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
            SpeedChart(samples)
            Text("Последние ${samples.size * 2} секунд активной сессии", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        } } }
        item { DiagnosticCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Скорость по маршрутам", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            state.activeServers().filter { it.enabled }.forEach { server ->
                val current = speeds["proxy-${server.id}"] ?: TrafficCounter()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, Modifier.weight(1f), maxLines = 1)
                    Text("↑ ${speed(current.uplink)}  ↓ ${speed(current.downlink)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (speeds.containsKey("qwdtt-vk")) {
                val qwdtt = speeds.getValue("qwdtt-vk")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("QWDTT", Modifier.weight(1f))
                    Text("↑ ${speed(qwdtt.uplink)}  ↓ ${speed(qwdtt.downlink)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            val direct = speeds["direct"] ?: TrafficCounter()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Напрямую", Modifier.weight(1f))
                Text("↑ ${speed(direct.uplink)}  ↓ ${speed(direct.downlink)}", style = MaterialTheme.typography.bodySmall)
            }
        } } }
        item { Button(onClick = { viewModel.checkExternalIps() }, enabled = status == VpnStatus.CONNECTED && !checking, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Public, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Проверяем маршруты…" else "Проверить IP каждого сервера")
        } }
        if (status != VpnStatus.CONNECTED) item { Text("Проверка внешнего IP доступна при активном VPN.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.activeServers().filter { ips.containsKey(it.id) }, key = { "ip-${it.id}" }) { server -> DiagnosticCard {
            ListItem(headlineContent = { Text(server.name) }, supportingContent = { Text(ips[server.id].orEmpty()) }, leadingContent = { Icon(Icons.Rounded.Lan, null) })
        } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Доступность", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.pingServers(state.activeGroup()?.id) }, enabled = !busy && monitoredServers.isNotEmpty()) {
                    Icon(Icons.Rounded.Refresh, "Проверить серверы сейчас")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthRange.entries.forEach { item ->
                    FilterChip(selected = range == item, onClick = { range = item }, label = { Text(item.title) })
                }
            }
            Text("При активном VPN: каждые 5 минут, после сбоев — каждую минуту", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (monitoredServers.isEmpty()) item { Text("Добавьте серверы для мониторинга", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(monitoredServers, key = { "health-${it.id}" }) { server ->
            val history = state.healthSamples.filter { it.serverId == server.id && it.timestamp >= rangeStart }
            HealthCard(server, history, now)
        }

        item { OutlinedButton(onClick = { logsOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Terminal, null)
            Spacer(Modifier.width(8.dp))
            Text("Открыть журнал")
            if (state.errorLogs.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Badge { Text(state.errorLogs.size.toString()) }
            }
        } }
        item { OutlinedButton(onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, viewModel.diagnosticText()) }
            context.startActivity(Intent.createChooser(intent, "Экспорт диагностики"))
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Экспорт без ключей и адресов") } }
    }

    if (logsOpen) LogsDialog(
        errors = state.errorLogs,
        sessionLogs = sessionLogs,
        onClear = viewModel::clearErrorLogs,
        onDismiss = { logsOpen = false },
    )
}

@Composable
private fun HealthCard(server: ServerNode, history: List<HealthSample>, now: Long) {
    val summary = HealthAnalytics.summarize(history, now)
    DiagnosticCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (summary.checks == 0) "Нет проверок за период"
                        else "Доступность ${summary.availabilityPercent}% · простой ${duration(summary.downtimeMillis)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (summary.checks > 0) Text(
                    if (summary.currentlyDown) "Недоступен" else "Доступен",
                    color = if (summary.currentlyDown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (history.isNotEmpty()) {
                HealthChart(history, now)
                Text("Красным отмечены периоды недоступности", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
                val latest = history.maxByOrNull { it.timestamp }
                Text(
                    "Проверок: ${summary.checks} · последний пинг: ${if (latest?.available == true) "${latest.latencyMs} мс" else "ошибка"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun HealthChart(samples: List<HealthSample>, now: Long) {
    val color = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    val data = remember(samples) { samples.sortedBy { it.timestamp } }
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 12.dp, bottom = 6.dp)) {
        if (data.isEmpty()) return@Canvas
        val start = data.first().timestamp
        val end = maxOf(data.last().timestamp + 1, now)
        val span = (end - start).coerceAtLeast(1)
        val maximum = data.filter { it.available }.maxOfOrNull { it.latencyMs }?.coerceAtLeast(1) ?: 1
        fun x(time: Long) = ((time - start).toFloat() / span * size.width).coerceIn(0f, size.width)
        data.forEachIndexed { index, sample ->
            if (!sample.available) {
                val next = data.getOrNull(index + 1)?.timestamp ?: end
                drawRect(error.copy(alpha = .3f), topLeft = Offset(x(sample.timestamp), 0f), size = androidx.compose.ui.geometry.Size((x(next) - x(sample.timestamp)).coerceAtLeast(3f), size.height))
                drawLine(error, Offset(x(sample.timestamp), 0f), Offset(x(sample.timestamp), size.height), strokeWidth = 2f)
                drawCircle(error, radius = 5f, center = Offset(x(sample.timestamp), size.height - 5f))
            }
        }
        val path = Path()
        var drawing = false
        data.forEach { sample ->
            if (sample.available) {
                val px = x(sample.timestamp)
                val py = size.height - sample.latencyMs.toFloat() / maximum * (size.height - 12f)
                if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
                drawCircle(color, radius = 4f, center = Offset(px, py))
                drawing = true
            } else drawing = false
        }
        drawPath(path, color, style = Stroke(width = 4f))
        drawLine(guide.copy(alpha = .2f), Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
    }
}

@Composable
private fun LogsDialog(errors: List<AppLogEntry>, sessionLogs: List<String>, onClear: () -> Unit, onDismiss: () -> Unit) {
    var showErrors by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Журнал", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (showErrors && errors.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteOutline, "Очистить ошибки") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    FilterChip(selected = showErrors, onClick = { showErrors = true }, label = { Text("Ошибки · ${errors.size}") })
                    FilterChip(selected = !showErrors, onClick = { showErrors = false }, label = { Text("Сессия · ${sessionLogs.size}") })
                }
                if (showErrors) {
                    if (errors.isEmpty()) Text("Ошибок нет", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(errors.asReversed(), key = { it.id }) { entry ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                SelectionContainer {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("${logTime(entry.timestamp)} · ${entry.source}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                        Text(entry.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (sessionLogs.isEmpty()) Text("Событий пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessionLogs.asReversed()) { line -> SelectionContainer { Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedChart(samples: List<TrafficSample>) {
    val color = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        val data = samples.takeLast(60)
        if (data.size < 2) return@Canvas
        val maximum = data.maxOf { maxOf(it.uploadBytesPerSecond, it.downloadBytesPerSecond) }.coerceAtLeast(1)
        fun path(upload: Boolean) = Path().apply {
            data.forEachIndexed { index, sample ->
                val x = index * size.width / (data.size - 1)
                val value = if (upload) sample.uploadBytesPerSecond else sample.downloadBytesPerSecond
                val y = size.height - value.toFloat() / maximum * size.height
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path(false), secondary, style = Stroke(width = 3f)); drawPath(path(true), color, style = Stroke(width = 3f))
        drawLine(color.copy(alpha = .2f), Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
    }
}

private fun duration(milliseconds: Long): String {
    val minutes = (milliseconds / 60_000.0).roundToInt()
    return when {
        milliseconds == 0L -> "0 мин"
        minutes < 1 -> "< 1 мин"
        minutes < 60 -> "$minutes мин"
        minutes < 1440 -> "${minutes / 60} ч ${minutes % 60} мин"
        else -> "${minutes / 1440} д ${minutes % 1440 / 60} ч"
    }
}

private fun logTime(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun speed(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ/с", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f КБ/с", bytes / 1024.0)
    else -> "$bytes Б/с"
}

@Composable
private fun DiagnosticCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        content = { Box(Modifier.padding(2.dp)) { content() } },
    )
}

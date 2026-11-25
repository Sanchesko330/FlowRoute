package dev.flowroute.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.flowroute.app.data.ServerNode
import dev.flowroute.app.data.TrafficCounter
import java.util.Locale
import kotlin.math.roundToInt

private val TrafficColors = listOf(
    Color(0xFF77E6B6), Color(0xFF7EB6FF), Color(0xFFFFC777),
    Color(0xFFC49AFF), Color(0xFFFF8D9A), Color(0xFF6ED7DF),
)

@Composable
internal fun TrafficCard(servers: List<ServerNode>, traffic: Map<String, TrafficCounter>, connected: Boolean) {
    val routes = servers.filter { it.enabled }.mapIndexed { index, server ->
        TrafficRoute(server.name, traffic["proxy-${server.id}"] ?: TrafficCounter(), TrafficColors[index % TrafficColors.size])
    } + listOfNotNull(
        traffic["qwdtt-vk"]?.let { TrafficRoute("QWDTT", it, Color(0xFFC49AFF)) },
        TrafficRoute("Напрямую", traffic["direct"] ?: TrafficCounter(), Color(0xFF94A1B2)),
    )
    val total = routes.sumOf { it.counter.total }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Распределение трафика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatBytes(total), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                drawRect(Color(0xFF1B232E), size = Size(size.width, size.height))
                if (total > 0) {
                    var start = 0f
                    routes.filter { it.counter.total > 0 }.forEach { route ->
                        val width = size.width * route.counter.total.toFloat() / total.toFloat()
                        drawRect(route.color, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = Size(width, size.height))
                        start += width
                    }
                }
            }
            if (total == 0L && !connected) {
                Text("Нет данных за текущую сессию", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                routes.forEach { route -> TrafficRouteRow(route, total) }
            }
        }
    }
}

private data class TrafficRoute(val name: String, val counter: TrafficCounter, val color: Color)

@Composable
private fun TrafficRouteRow(route: TrafficRoute, total: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(route.color))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("↑ ${formatBytes(route.counter.uplink)}  ↓ ${formatBytes(route.counter.downlink)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatBytes(route.counter.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val percent = if (total > 0) (route.counter.total * 100.0 / total).roundToInt() else 0
            Text("$percent%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.2f ГБ", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

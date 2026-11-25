package dev.flowroute.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.flowroute.app.data.VpnStatus

private val HeroMint = Color(0xFF77E6B6)
private val HeroBlue = Color(0xFF79AFFF)
private val HeroError = Color(0xFFFF9B9B)

@Composable
internal fun ConnectionHero(
    status: VpnStatus,
    activeServers: Int,
    activeRules: Int,
    onToggle: () -> Unit,
    canConnect: Boolean = activeServers > 0,
    qwdttAvailable: Boolean = false,
    qwdttActive: Boolean = false,
    onSwitchChannel: () -> Unit = {},
) {
    val accent by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> HeroMint
            VpnStatus.CONNECTING -> HeroBlue
            VpnStatus.ERROR -> HeroError
            VpnStatus.DISCONNECTED -> HeroMint
        }, animationSpec = tween(700), label = "accent",
    )
    val transition = rememberInfiniteTransition(label = "connection")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "pulse")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(if (status == VpnStatus.CONNECTING) 2200 else 9000, easing = LinearEasing)), "rotation")
    val ringWidth by animateFloatAsState(if (status == VpnStatus.CONNECTED) 1.06f else 1f, tween(900), label = "ring")
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A22)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.background(Brush.radialGradient(listOf(accent.copy(alpha = .105f), Color.Transparent)))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(166.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(accent.copy(alpha = .035f + pulse * .035f), radius = size.minDimension * .48f * ringWidth)
                        rotate(rotation) {
                            drawArc(accent.copy(alpha = .22f + pulse * .2f), 18f, if (status == VpnStatus.CONNECTING) 105f else 58f,
                                false, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                        }
                        rotate(-rotation * .65f) {
                            drawArc(accent.copy(alpha = .12f), 185f, if (status == VpnStatus.CONNECTING) 72f else 40f,
                                false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    Box(
                        Modifier.size((92 + pulse * 5).dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = .76f))))
                            .clickable(enabled = canConnect && status != VpnStatus.CONNECTING, onClick = onToggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, "Включить или выключить VPN", Modifier.size(38.dp).scale(1f + pulse * .035f), tint = Color(0xFF0B0F14))
                    }
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = status,
                    transitionSpec = {
                        (slideInVertically(tween(420)) { it / 2 } + fadeIn(tween(420))) togetherWith
                            (slideOutVertically(tween(300)) { -it / 3 } + fadeOut(tween(220)))
                    }, label = "status",
                ) { current ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(when (current) {
                            VpnStatus.DISCONNECTED -> "Готов к подключению"
                            VpnStatus.CONNECTING -> "Устанавливаем маршруты"
                            VpnStatus.CONNECTED -> "Вы подключены"
                            VpnStatus.ERROR -> "Не удалось подключиться"
                        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (current == VpnStatus.CONNECTED) {
                            Text("$activeServers серверов · $activeRules правил", color = Color(0xFF94A1B2), style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                if (status == VpnStatus.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(.7f).padding(top = 20.dp).height(3.dp).clip(CircleShape),
                        color = accent, trackColor = accent.copy(alpha = .12f))
                }
                Button(
                    onClick = onToggle,
                    enabled = canConnect && status != VpnStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF0B0F14)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                ) {
                    AnimatedContent(if (status == VpnStatus.CONNECTED) "Отключить" else if (status == VpnStatus.CONNECTING) "Подключение…" else "Подключить", label = "button") {
                        Text(it, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (qwdttAvailable) OutlinedButton(
                    onClick = onSwitchChannel,
                    enabled = status == VpnStatus.CONNECTED,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.SwapHoriz, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (qwdttActive) "Переключиться на VLESS" else "Переключиться на QWDTT")
                }
                if (!canConnect) Text("Добавьте и включите хотя бы один сервер", color = Color(0xFF94A1B2),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

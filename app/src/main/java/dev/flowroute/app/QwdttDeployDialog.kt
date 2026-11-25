package dev.flowroute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flowroute.app.core.QwdttDeployRequest
import dev.flowroute.app.core.QwdttDeployer

@Composable
internal fun QwdttDeployDialog(groupId: String, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    var vkHash by remember { mutableStateOf("") }
    var dns1 by remember { mutableStateOf("1.1.1.1") }
    var dns2 by remember { mutableStateOf("1.0.0.1") }
    val deployState by QwdttDeployer.state.collectAsStateWithLifecycle()
    val port = sshPort.toIntOrNull()
    val valid = host.isNotBlank() && port in 1..65535 && vkHash.isNotBlank() &&
        (password.isNotBlank() || privateKey.isNotBlank()) && !deployState.running

    Dialog(onDismissRequest = { if (!deployState.running) onDismiss() }) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("Установка QWDTT", style = MaterialTheme.typography.headlineSmall) }
                item { Text("На VPS будут установлены сервер, системная служба и правила брандмауэра.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { OutlinedTextField(host, { host = it.trim() }, label = { Text("IP или домен VPS") }, singleLine = true, enabled = !deployState.running, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(user, { user = it }, label = { Text("SSH-пользователь") }, singleLine = true, enabled = !deployState.running, modifier = Modifier.weight(1f))
                        OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = port == null || port !in 1..65535,
                            enabled = !deployState.running, modifier = Modifier.width(100.dp))
                    }
                }
                item { OutlinedTextField(password, { password = it }, label = { Text("Пароль SSH / sudo") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !deployState.running, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(privateKey, { privateKey = it }, label = { Text("Закрытый ключ (вместо пароля)") }, minLines = 3, maxLines = 5,
                    enabled = !deployState.running, modifier = Modifier.fillMaxWidth()) }
                if (privateKey.isNotBlank()) item {
                    OutlinedTextField(keyPassphrase, { keyPassphrase = it }, label = { Text("Парольная фраза ключа") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !deployState.running, modifier = Modifier.fillMaxWidth())
                }
                item { OutlinedTextField(vkHash, { vkHash = it.trim() }, label = { Text("Хеш VK-звонка") }, singleLine = true, enabled = !deployState.running, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(dns1, { dns1 = it.trim() }, label = { Text("DNS 1") }, singleLine = true, enabled = !deployState.running, modifier = Modifier.weight(1f))
                        OutlinedTextField(dns2, { dns2 = it.trim() }, label = { Text("DNS 2") }, singleLine = true, enabled = !deployState.running, modifier = Modifier.weight(1f))
                    }
                }
                if (deployState.step.isNotBlank()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (deployState.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(deployState.step, color = if (deployState.error.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (deployState.error.isNotBlank()) Text(deployState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("SSH-пароль и ключ не сохраняются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Button(onClick = {
                        viewModel.deployQwdtt(groupId, QwdttDeployRequest(host, port ?: 22, user, password, privateKey, keyPassphrase, vkHash, dns1, dns2))
                    }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Установить на сервер") }
                }
                item { TextButton(onClick = onDismiss, enabled = !deployState.running, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") } }
            }
        }
    }
}

package dev.flowroute.app.core

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.flowroute.app.data.QwdttConfig
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class QwdttDeployRequest(
    val host: String,
    val sshPort: Int = 22,
    val user: String = "root",
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val vkHash: String,
    val dns1: String = "1.1.1.1",
    val dns2: String = "1.0.0.1",
)

data class QwdttDeployState(
    val running: Boolean = false,
    val step: String = "",
    val error: String = "",
)

object QwdttDeployer {
    private val mutableState = MutableStateFlow(QwdttDeployState())
    val state = mutableState.asStateFlow()

    suspend fun deploy(context: Context, request: QwdttDeployRequest, groupId: String): QwdttConfig = withContext(Dispatchers.IO) {
        require(request.host.isNotBlank()) { "Укажите адрес VPS" }
        require(request.vkHash.isNotBlank()) { "Укажите хеш VK-звонка" }
        require(request.privateKey.isNotBlank() || request.password.isNotBlank()) { "Укажите SSH-пароль или закрытый ключ" }
        mutableState.value = QwdttDeployState(true, "Подключение по SSH")
        var session: Session? = null
        try {
            val adminToken = randomSecret()
            val mainPassword = randomSecret()
            session = createSession(request)
            upload(session, context, adminToken, mainPassword)
            mutableState.value = QwdttDeployState(true, "Установка и запуск серверной части")
            val dns = listOf(request.dns1.trim(), request.dns2.trim()).filter { it.isNotEmpty() }.joinToString(",")
            val command = "env WDTT_DNS_SERVERS=${shellQuote(dns)} WDTT_DTLS_PORT=56000 WDTT_WG_PORT=56001 WDTT_SSH_PORT=${request.sshPort} bash /tmp/deploy.sh"
            val output = exec(session, rootCommand(command, request), 10 * 60_000L)
            val pin = Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").find(output)?.groupValues?.get(1)
                ?: error("Сервер установлен, но не получен отпечаток админ-панели")
            mutableState.value = QwdttDeployState(true, "Создание QWDTT-конфига")
            val password = createAdminPassword(request.host, adminToken, pin, request.vkHash)
            QwdttConfig(UUID.randomUUID().toString(), groupId, "QWDTT ${request.host}", request.host,
                request.vkHash.trim(), 9, 9000, password).also {
                mutableState.value = QwdttDeployState(false, "Сервер установлен")
            }
        } catch (error: Throwable) {
            mutableState.value = QwdttDeployState(false, "Ошибка установки", error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            session?.disconnect()
        }
    }

    private fun createSession(request: QwdttDeployRequest): Session {
        val jsch = JSch()
        if (request.privateKey.isNotBlank()) {
            jsch.addIdentity("flowroute-deploy", request.privateKey.toByteArray(), null,
                request.keyPassphrase.takeIf { it.isNotBlank() }?.toByteArray())
        }
        return jsch.getSession(request.user.trim().ifBlank { "root" }, request.host.trim(), request.sshPort).apply {
            if (request.privateKey.isBlank()) setPassword(request.password)
            setConfig(Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("ServerAliveInterval", "10000")
                put("ServerAliveCountMax", "3")
            })
            connect(15_000)
        }
    }

    private fun upload(session: Session, context: Context, adminToken: String, mainPassword: String) {
        mutableState.value = QwdttDeployState(true, "Загрузка файлов на VPS")
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(15_000)
            context.assets.open("qwdtt-deploy.sh").use { channel.put(it, "/tmp/deploy.sh") }
            context.assets.open("qwdtt-server").use { channel.put(it, "/tmp/wdtt-server") }
            channel.put(ByteArrayInputStream(adminToken.toByteArray()), "/tmp/wdtt-admin.token")
            channel.put(ByteArrayInputStream(mainPassword.toByteArray()), "/tmp/wdtt-main.password")
            channel.put(ByteArrayInputStream(ByteArray(0)), "/tmp/wdtt-bot.token")
        } finally {
            channel.disconnect()
        }
    }

    private fun exec(session: Session, command: String, timeoutMs: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = StringBuilder()
        try {
            channel.setCommand(command)
            val input = channel.inputStream
            val errors = channel.errStream
            channel.connect(15_000)
            val started = System.currentTimeMillis()
            while (true) {
                readAvailable(input, output); readAvailable(errors, output)
                if (channel.isClosed) {
                    readAvailable(input, output); readAvailable(errors, output)
                    break
                }
                if (System.currentTimeMillis() - started > timeoutMs) error("Таймаут установки сервера")
                Thread.sleep(150)
            }
            if (channel.exitStatus != 0) error("Установщик завершился с ошибкой: ${output.toString().takeLast(1200)}")
            val progress = Regex("WDTT_PROGRESS\\|([0-9.]+)\\|([^\\r\\n]+)").findAll(output).lastOrNull()
            if (progress != null) mutableState.value = QwdttDeployState(true, progress.groupValues[2])
            return output.toString()
        } finally {
            channel.disconnect()
        }
    }

    private fun readAvailable(stream: java.io.InputStream, output: StringBuilder) {
        val buffer = ByteArray(8192)
        while (stream.available() > 0) {
            val count = stream.read(buffer)
            if (count <= 0) break
            output.append(String(buffer, 0, count))
        }
    }

    private fun rootCommand(command: String, request: QwdttDeployRequest): String {
        if (request.user.trim().equals("root", true)) return command
        require(request.password.isNotBlank()) { "Для sudo укажите пароль пользователя" }
        return "printf '%s\\n' ${shellQuote(request.password)} | sudo -S -p '' $command"
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun randomSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun createAdminPassword(host: String, token: String, pin: String, vkHash: String): String {
        val expected = Base64.decode(pin.removePrefix("sha256/"), Base64.DEFAULT)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: error("Пустой сертификат админ-панели")
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                if (!MessageDigest.isEqual(expected, actual)) error("Сертификат админ-панели не совпадает с установленным")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val connection = java.net.URI("https://$host:56002/admin/passwords").toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val form = listOf("vk_hash" to vkHash.trim(), "days" to "365", "max_devices" to "10", "label" to "FlowRoute")
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        try {
            connection.outputStream.use { it.write(form.toByteArray()) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Админ-API QWDTT: HTTP $code $body")
            return kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["password"]?.jsonPrimitive?.content
                ?: error("Админ-API не вернул пароль подключения")
        } finally {
            connection.disconnect()
        }
    }
}

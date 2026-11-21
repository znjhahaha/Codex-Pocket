package dev.codexpocket.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexpocket.wire.*
import kotlinx.coroutines.*

@Composable
internal fun SshLocationSheet(progress: String?, onDismiss: () -> Unit, onConnect: (String, SshTarget) -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    // Secret input is not placed in the Activity's unencrypted saved-state bundle.
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var fingerprint by remember(host, port) { mutableStateOf<String?>(null) }
    var confirmed by remember(host, port) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                privateKey = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val data = stream.readNBytes(65537); require(data.size <= 65536)
                        data.toString(Charsets.UTF_8).also { require(it.startsWith("-----BEGIN ")) }
                    } ?: error("Unable to open key")
                }
            } catch (_: Exception) { error = "私钥无法读取，请选择 PEM 或 OpenSSH 私钥文件。" }
        }
    }
    val busy = probing || progress != null
    val valid = host.isNotBlank() && port.toIntOrNull() in 1..65535 && username.isNotBlank() &&
        if (useKey) privateKey != null else password.isNotEmpty()
    PocketSheet("SSH 远程会话", onDismiss, subtitle = "连接 Linux，把项目留在远程电脑") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PocketTextField(label, { label = it }, label = "名称（可选）", singleLine = true)
            PocketTextField(host, { host = it.trim(); error = null }, label = "主机地址", singleLine = true, modifier = Modifier.testTag("ssh-host"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PocketTextField(username, { username = it }, label = "用户名", singleLine = true, modifier = Modifier.weight(1f).testTag("ssh-user"))
                PocketTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = "端口", singleLine = true, modifier = Modifier.width(90.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketPill("密码", !useKey, { useKey = false }, modifier = Modifier.weight(1f))
                PocketPill("私钥", useKey, { useKey = true }, modifier = Modifier.weight(1f))
            }
            if (useKey) {
                PocketButton(if (privateKey == null) "选择私钥文件" else "已选择私钥 · 更换", { keyPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), secondary = true)
                PocketTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = "私钥密码（可选）",
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
            } else PocketTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("ssh-password"), label = "登录密码",
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            fingerprint?.let { value ->
                UsageCard {
                    Text("确认主机身份", style = MaterialTheme.typography.titleSmall)
                    Text(value, style = MaterialTheme.typography.bodySmall)
                    Text("与服务器管理员提供的 SHA256 指纹核对一致后继续。", style = MaterialTheme.typography.bodySmall)
                    Row { Checkbox(confirmed, { confirmed = it }); Text("指纹一致，信任此主机", Modifier.padding(top = 12.dp)) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            progress?.let { PocketProgressStatus(it) }
            Text("将安装签名的 Pocket 后台到当前用户目录。断开手机不会停止远程任务。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PocketButton(if (probing) "正在读取主机身份" else if (fingerprint == null) "检查主机" else "连接并准备",
                onClick = {
                    if (fingerprint == null) scope.launch {
                        probing = true; error = null
                        try { fingerprint = probeSshHostKey(host, port.toInt()) }
                        catch (_: Exception) { error = "无法连接 SSH，请检查地址和端口。" }
                        finally { probing = false }
                    } else if (confirmed) {
                        try {
                            val authentication = if (useKey) SshAuthentication.PrivateKey(privateKey!!, passphrase) else SshAuthentication.Password(password)
                            onConnect(label.ifBlank { host }, SshTarget(host, port.toInt(), username, fingerprint!!, authentication, 43821))
                        } catch (_: Exception) { error = "请检查地址、端口和认证信息。" }
                    }
                }, Modifier.fillMaxWidth().testTag("ssh-connect"), enabled = valid && !busy && (fingerprint == null || confirmed))
        }
    }
}

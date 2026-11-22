package dev.codexpocket.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.*
import dev.codexpocket.wire.*
import java.io.InputStream
import kotlinx.coroutines.*

/** A PEM or OpenSSH private key as text, bounded before it is held in memory. */
internal fun readSshPrivateKey(stream: InputStream): String {
    val data = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(data.size() + count <= 65536)
        data.write(buffer, 0, count)
    }
    return data.toString("UTF-8").also { require(it.startsWith("-----BEGIN ")) }
}

/** Opens the system file picker; the chosen key's text reaches [onKey] and never leaves this process. */
@Composable
internal fun rememberSshKeyPicker(onKey: (String) -> Unit, onFailure: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                onKey(withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readSshPrivateKey) ?: error("Unable to open key")
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    return { picker.launch(arrayOf("*/*")) }
}

/**
 * Adds a Linux host. The host key is read first and must be confirmed against what the server's
 * administrator gave out; only then can credentials be sent and the host prepared.
 */
@Composable
internal fun SshLocationSheet(progress: String?, onDismiss: () -> Unit, onConnect: (String, SshTarget) -> Unit,
    probe: suspend (String, Int) -> String = ::probeSshHostKey,
    keyPicker: @Composable (onKey: (String) -> Unit, onFailure: () -> Unit) -> (() -> Unit) = { onKey, onFailure -> rememberSshKeyPicker(onKey, onFailure) }) {
    var label by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    // Secrets and the trust decision live only in this composition, never in the Activity's saved state.
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var fingerprint by remember(host, port) { mutableStateOf<String?>(null) }
    var confirmed by remember(host, port) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pickKey = keyPicker({ privateKey = it; error = null }, { error = "私钥无法读取，请选择 PEM 或 OpenSSH 私钥文件。" })
    val busy = probing || progress != null
    val valid = host.isNotBlank() && port.toIntOrNull() in 1..65535 && username.isNotBlank() &&
        if (useKey) privateKey != null else password.isNotEmpty()
    val plain = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
    val secret = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
    PSheet("SSH 远程会话", onDismiss, subtitle = "连接 Linux，把项目留在远程电脑", maxHeightFraction = .9f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("ssh-sheet")) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("ssh-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PTextField(label, { label = it }, Modifier.testTag("ssh-label"), label = "名称（可选）", enabled = progress == null, singleLine = true)
                PTextField(host, { host = it.trim(); error = null }, Modifier.testTag("ssh-host"), label = "主机地址", enabled = progress == null,
                    singleLine = true, keyboardOptions = plain.copy(keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PTextField(username, { username = it; error = null }, Modifier.weight(1f).testTag("ssh-user"), label = "用户名", enabled = progress == null,
                        singleLine = true, keyboardOptions = plain)
                    PTextField(port, { port = it.filter(Char::isDigit).take(5); error = null }, Modifier.width(96.dp).testTag("ssh-port"), label = "端口",
                        enabled = progress == null, singleLine = true, keyboardOptions = plain.copy(keyboardType = KeyboardType.Number))
                }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PPill("密码", !useKey, { useKey = false; error = null }, Modifier.weight(1f).testTag("ssh-auth:password"), enabled = progress == null)
                    PPill("私钥", useKey, { useKey = true; error = null }, Modifier.weight(1f).testTag("ssh-auth:key"), enabled = progress == null)
                }
                if (useKey) {
                    PButton(if (privateKey == null) "选择私钥文件" else "已选择私钥 · 更换", pickKey, Modifier.fillMaxWidth().testTag("ssh-key"),
                        kind = PButtonKind.Secondary, enabled = progress == null)
                    PTextField(passphrase, { passphrase = it }, Modifier.testTag("ssh-passphrase"), label = "私钥密码（可选）", enabled = progress == null,
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret)
                } else PTextField(password, { password = it; error = null }, Modifier.testTag("ssh-password"), label = "登录密码", enabled = progress == null,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret)
                fingerprint?.let { value ->
                    PCard(Modifier.fillMaxWidth().testTag("ssh-host-key"), spacing = 6.dp) {
                        PText("确认主机身份", Modifier.semantics { heading() }, style = Pocket.type.titleSmall)
                        PText(value, Modifier.testTag("ssh-fingerprint"), style = Pocket.type.mono)
                        PText("与服务器管理员提供的 SHA256 指纹核对一致后继续。", style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
                        Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch)
                            .pocketToggleable(confirmed, progress == null, Role.Checkbox) { confirmed = it }.testTag("ssh-trust"),
                            verticalAlignment = Alignment.CenterVertically) {
                            PCheck(confirmed, null)
                            PText("指纹一致，信任此主机", Modifier.weight(1f), style = Pocket.type.label)
                        }
                    }
                }
                error?.let { PText(it, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("ssh-error"),
                    style = Pocket.type.bodySmall, color = Pocket.colors.danger) }
                progress?.let { PocketProgressStatus(it, Modifier.padding(horizontal = 4.dp).testTag("ssh-progress")) }
                PText("将安装签名的 Pocket 后台到当前用户目录。断开手机不会停止远程任务。", Modifier.padding(horizontal = 4.dp),
                    style = Pocket.type.caption, color = Pocket.colors.inkMuted)
                Spacer(Modifier.height(6.dp))
            }
            PButton(if (probing) "正在读取主机身份" else if (fingerprint == null) "检查主机" else "连接并准备",
                onClick = {
                    if (fingerprint == null) scope.launch {
                        probing = true; error = null
                        try { fingerprint = probe(host, port.toInt()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "无法连接 SSH，请检查地址和端口。" }
                        finally { probing = false }
                    } else if (confirmed) {
                        try {
                            val authentication = if (useKey) SshAuthentication.PrivateKey(privateKey!!, passphrase) else SshAuthentication.Password(password)
                            onConnect(label.ifBlank { host }, SshTarget(host, port.toInt(), username, fingerprint!!, authentication, 43821))
                        } catch (_: Exception) { error = "请检查地址、端口和认证信息。" }
                    }
                }, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("ssh-connect"), enabled = valid && !busy && (fingerprint == null || confirmed))
        }
    }
}

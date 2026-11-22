package dev.codexpocket.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.LocalAccount

/** Running Codex on this phone: start it, sign it in, stop it. */
@Composable
internal fun ThisPhoneSheet(added: Boolean, account: LocalAccount, progress: String?, onDismiss: () -> Unit, onStart: () -> Unit,
    onChatGpt: () -> Unit, onApiKey: (String) -> Unit, onRefresh: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    // A key is not placed in the Activity's unencrypted saved-state bundle.
    var apiKey by remember { mutableStateOf("") }
    var enteringKey by remember { mutableStateOf(false) }
    PocketSheet("此手机", onDismiss, subtitle = "Codex 直接在手机上运行，不需要电脑") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("项目、命令和登录信息都留在本应用的私有目录内，其它应用和你的相册、文件不可访问。Codex 自带的命令沙箱在手机上不可用，所以每条不在安全名单内的命令都会先问你。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!added) {
                progress?.let { PocketProgressStatus(it) }
                PocketButton("在此手机上运行", onStart, Modifier.fillMaxWidth().testTag("this-phone-start"), enabled = progress == null)
                return@Column
            }
            when (account) {
                LocalAccount.Unknown, LocalAccount.Checking -> PocketProgressStatus("正在读取登录状态", Modifier.testTag("this-phone-checking"))
                is LocalAccount.SignedIn -> Text("已登录 · ${account.label}", Modifier.testTag("this-phone-signed-in"), style = MaterialTheme.typography.titleSmall)
                is LocalAccount.Code -> {
                    Text("在浏览器中打开登录页面，输入下面的一次性验证码。", style = MaterialTheme.typography.bodyMedium)
                    Text(account.code, Modifier.testTag("this-phone-code"), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PocketButton("打开登录页面", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            Modifier.weight(1f).testTag("this-phone-open-login"))
                        PocketButton("复制验证码", { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codex", account.code)) },
                            Modifier.weight(1f), secondary = true)
                    }
                    PocketProgressStatus("等待你在浏览器中完成登录")
                }
                LocalAccount.SignedOut, is LocalAccount.Failed -> {
                    if (account is LocalAccount.Failed) Text(account.reason, Modifier.testTag("this-phone-failed"), color = MaterialTheme.colorScheme.error)
                    else Text("尚未登录，登录后才能开始对话。", style = MaterialTheme.typography.bodyMedium)
                    PocketButton("用 ChatGPT 账号登录", onChatGpt, Modifier.fillMaxWidth().testTag("this-phone-chatgpt"))
                    if (enteringKey) {
                        PocketTextField(apiKey, { apiKey = it.trim() }, Modifier.fillMaxWidth().testTag("this-phone-api-key"), label = "OpenAI API Key",
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        PocketButton("保存并登录", { onApiKey(apiKey); apiKey = ""; enteringKey = false }, Modifier.fillMaxWidth().testTag("this-phone-api-key-save"),
                            enabled = apiKey.length in 20..512, secondary = true)
                    } else PocketButton("改用 API Key", { enteringKey = true }, Modifier.fillMaxWidth().testTag("this-phone-use-api-key"), secondary = true)
                    if (account is LocalAccount.Failed) PocketButton("重新读取登录状态", onRefresh, Modifier.fillMaxWidth(), secondary = true)
                }
            }
            PocketButton("停止在此手机上运行", onStop, Modifier.fillMaxWidth().testTag("this-phone-stop"), secondary = true, destructive = true)
            Text("停止会结束手机上正在进行的任务；会话记录和登录信息会保留，再次选择“此手机”即可继续。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

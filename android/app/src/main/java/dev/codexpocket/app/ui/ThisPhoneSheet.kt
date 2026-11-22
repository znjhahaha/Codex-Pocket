package dev.codexpocket.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.codexpocket.app.data.LocalAccount
import dev.codexpocket.app.data.LocalProjects
import dev.codexpocket.app.ui.kit.*

/** Running Codex on this phone: start it, sign it in, stop it. */
@Composable
internal fun ThisPhoneSheet(added: Boolean, account: LocalAccount, progress: String?, onDismiss: () -> Unit, onStart: () -> Unit,
    onChatGpt: () -> Unit, onApiKey: (String) -> Unit, onRefresh: () -> Unit, onStop: () -> Unit,
    projects: LocalProjects = LocalProjects(), onImport: (Uri) -> Unit = {}, onExport: (String, Uri) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf<String?>(null) }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImport(uri) }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val name = exporting; exporting = null
        if (uri != null && name != null) onExport(name, uri)
    }
    // A key is not placed in the Activity's unencrypted saved-state bundle.
    var apiKey by remember { mutableStateOf("") }
    var enteringKey by remember { mutableStateOf(false) }
    PSheet("此手机", onDismiss, subtitle = "Codex 直接在手机上运行，不需要电脑", maxHeightFraction = .9f) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PText("项目、命令和登录信息都留在本应用的私有目录内，其它应用和你的相册、文件不可访问。Codex 自带的命令沙箱在手机上不可用，所以每条不在安全名单内的命令都会先问你。",
                style = Pocket.type.bodySmall, color = Pocket.colors.inkMuted)
            if (!added) {
                progress?.let { PocketProgressStatus(it) }
                PButton("在此手机上运行", onStart, Modifier.fillMaxWidth().testTag("this-phone-start"), enabled = progress == null)
                return@Column
            }
            when (account) {
                LocalAccount.Unknown, LocalAccount.Checking -> PocketProgressStatus("正在读取登录状态", Modifier.testTag("this-phone-checking"))
                is LocalAccount.SignedIn -> PText("已登录 · ${account.label}", Modifier.testTag("this-phone-signed-in"), style = Pocket.type.titleSmall)
                is LocalAccount.Code -> {
                    PText("在浏览器中打开登录页面，输入下面的一次性验证码。", style = Pocket.type.body)
                    PText(account.code, Modifier.testTag("this-phone-code"), style = Pocket.type.headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PButton("打开登录页面", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            Modifier.weight(1f).testTag("this-phone-open-login"))
                        PButton("复制验证码", { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codex", account.code)) },
                            Modifier.weight(1f), kind = PButtonKind.Secondary)
                    }
                    PocketProgressStatus("等待你在浏览器中完成登录")
                }
                LocalAccount.SignedOut, is LocalAccount.Failed -> {
                    if (account is LocalAccount.Failed) PText(account.reason, Modifier.testTag("this-phone-failed"), style = Pocket.type.body, color = Pocket.colors.danger)
                    else PText("尚未登录，登录后才能开始对话。", style = Pocket.type.body)
                    PButton("用 ChatGPT 账号登录", onChatGpt, Modifier.fillMaxWidth().testTag("this-phone-chatgpt"))
                    if (enteringKey) {
                        PTextField(apiKey, { apiKey = it.trim() }, Modifier.testTag("this-phone-api-key"), label = "OpenAI API Key",
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        PButton("保存并登录", { onApiKey(apiKey); apiKey = ""; enteringKey = false }, Modifier.fillMaxWidth().testTag("this-phone-api-key-save"),
                            kind = PButtonKind.Secondary, enabled = apiKey.length in 20..512)
                    } else PButton("改用 API Key", { enteringKey = true }, Modifier.fillMaxWidth().testTag("this-phone-use-api-key"), kind = PButtonKind.Secondary)
                    if (account is LocalAccount.Failed) PButton("重新读取登录状态", onRefresh, Modifier.fillMaxWidth(), kind = PButtonKind.Secondary)
                }
            }
            PText("项目文件", style = Pocket.type.titleSmall)
            PText("手机上的项目放在本应用的私有目录里。用压缩包把代码带进来，或把改好的项目带出去。", style = Pocket.type.caption, color = Pocket.colors.inkMuted)
            projects.names.forEach { name ->
                Row(Modifier.fillMaxWidth().testTag("this-phone-project:$name"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PText(name, Modifier.weight(1f), style = Pocket.type.body)
                    PButton("导出", { exporting = name; pickDestination.launch("$name.zip") }, Modifier.testTag("this-phone-export:$name"),
                        kind = PButtonKind.Secondary, enabled = !projects.busy)
                }
            }
            if (projects.busy) PocketProgressStatus("正在处理项目文件", Modifier.testTag("this-phone-transfer"))
            projects.notice?.let { PText(it, Modifier.testTag("this-phone-transfer-notice"), style = Pocket.type.bodySmall) }
            PButton("导入项目压缩包", { pickArchive.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                Modifier.fillMaxWidth().testTag("this-phone-import"), kind = PButtonKind.Secondary, enabled = !projects.busy)
            PButton("停止在此手机上运行", onStop, Modifier.fillMaxWidth().testTag("this-phone-stop"), kind = PButtonKind.Danger)
            PText("停止会结束手机上正在进行的任务；会话记录和登录信息会保留，再次选择“此手机”即可继续。", style = Pocket.type.caption, color = Pocket.colors.inkMuted)
        }
    }
}
